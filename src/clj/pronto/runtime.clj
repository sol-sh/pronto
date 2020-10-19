(ns pronto.runtime
  (:refer-clojure
   :exclude [get])
  (:require [pronto.emitters :as e]
            [pronto.utils :as u])

  (:import [com.google.protobuf GeneratedMessageV3$Builder]
           [pronto ProtoMap]))


(defmacro rget [m k]
  (if-not (keyword? k)
    `(clojure.core/get ~m ~k)
    (let [m2            (u/with-type-hint 'm2 ProtoMap)
          intf-info     (e/interface-info k)
          intf-name     (e/intf-info->intf-name intf-info)
          val-at-method (e/val-at-intf-name2 intf-info)]
      `(let [~m2 ~m]
         (when-not (nil? ~m2)
           (~(symbol (str "." val-at-method))
            ~(with-meta m2
               {:tag (str "pronto.protos." intf-name)})))))))


(defn emit-assoc-method [m k v builder]
  (let [intf-info    (e/interface-info k)
        intf-name    (e/intf-info->intf-name intf-info)
        assoc-method (e/assoc-intf-name2 intf-info)]
    `(~(symbol (str "." assoc-method))
      ~(with-meta m
         {:tag (str "pronto.protos." intf-name)})
      ~builder
      ~v)))


(defn emit-empty-method [m k]
  (let [intf-info    (e/interface-info k)
        intf-name    (e/intf-info->intf-name intf-info)
        empty-method (e/empty-intf-name2 intf-info)]
    `(~(symbol (str "." empty-method))
      ~(with-meta m
         {:tag (str "pronto.protos." intf-name)}))))


(defmacro rassoc! [m k v]
  (if-not (keyword? k)
    `(clojure.core/assoc! ~m ~k ~v)
    (let [m2           (u/with-type-hint 'm2 ProtoMap)
          intf-info    (e/interface-info k)
          intf-name    (e/intf-info->intf-name intf-info)
          assoc-method (e/assoc-intf-name2 intf-info)
          builder      (u/with-type-hint
                         (gensym 'builder)
                         GeneratedMessageV3$Builder)]
      `(let [~m2      ~m
             ~builder (.pmap_getBuilder ~m2)]
         (~(symbol (str "." assoc-method))
          ~(with-meta m2
             {:tag (str "pronto.protos." intf-name)})
          ~builder
          ~v)
         ~m2))))


(defmacro transform-in [m kvs]
  (let [m2         (gensym 'm2)
        new-submap (gensym 'newsubmap)
        submap     (gensym 'submap)
        kv-forest  (u/kv-forest kvs)]
    `(let [~m2 (transient ~m)]
       ~@(for [[k vs] kv-forest
               v      (partition-by u/leaf? vs)]
           (if (u/leaf? (first v))
             (let [val-fn (eval (u/leaf-val (first v)))]
               (val-fn m2 k))
             `(let [~submap     (or (rget ~m2 ~k)
                                    ~(emit-empty-method m2 k))
                    ~new-submap (pronto.runtime/transform-in ~submap ~(u/flatten-forest v))]
                (rassoc! ~m2 ~k ~new-submap))))
       (persistent! ~m2))))


(defn- assoc-transform-kvs [rewrite-fn kvs]
  (->> kvs
       (partition 2)
       (map
         (fn [[ks v]]
           [ks
            (fn [msym k]
              (rewrite-fn
                `(rassoc!
                   ~msym ~k ~v)))]))))


(defn- update-transform-kv [rewrite-fn ks f args]
  [[ks
    (fn [msym k]
      (rewrite-fn
        `(let [x# (rget ~msym ~k)
               x# (~f x# ~@args)]
           (rassoc! ~msym ~k x#))))]])


(defn- assoc-args->assoc-in-args [k v kvs]
  (->> kvs
       (cons v)
       (cons k)
       (partition 2)
       (mapcat
         (fn [[k v]]
           [[k] v]))))


(defn- fn-name [form]
  (when (list? form)
    (when-let [fst (first form)]
      (when (symbol? fst)
        fst))))


(def ^:private update?
  (comp #{'update 'update-in} fn-name))


(def ^:private assoc?
  (comp #{'assoc 'assoc-in} fn-name))


(defn- safe-resolve [x]
  (try
    (resolve x)
    (catch Exception _)))

(defn- transient-proto-fn? [form]
  (let [fn-name (fn-name form)
        v       (safe-resolve fn-name)
        m       (meta v)]
    (->> m
         :arglists
         (some #(when (= (count form) (count %)) %))
         first
         meta
         :transient-proto
         boolean)))


(defn- transformation? [form]
  (or
    (assoc? form)
    (update? form)
    (transient-proto-fn? form)))


(defn- rewrite-transformation [g transforms]
  `(transform-in
     ~g
     ~(mapcat
        (fn [form]
          (let [rewrite-fn (let [pred (-> form meta :predicate)]
                             (if (some? pred)
                               (fn [form]
                                 `(when ~pred
                                    ~form))
                               identity))]
            (case (name (fn-name form))
              "assoc"
              (let [[_ k v & kvs] form]
                (assoc-transform-kvs
                  rewrite-fn
                  (assoc-args->assoc-in-args k v kvs)))
              "assoc-in"
              (let [[_ [k & ks] v] form]
                (assoc-transform-kvs
                  rewrite-fn
                  [(into [k] ks) v]))
              "update"
              (let [[_ k f & args] form]
                (update-transform-kv rewrite-fn [k] f args))
              "update-in"
              (let [[_ ks f & args] form]
                (update-transform-kv rewrite-fn ks f args))
              (let [[f args] form]
                [[[(keyword (gensym))]
                  (fn [msym _]
                    (rewrite-fn
                      `(~f ~msym ~args)))]]))))
        transforms)))


(defn- rewrite-forms [g forms]
  (->> forms
       (partition-by
         (fn [form]
           (if (transformation? form)
             ::transformation
             form)))
       (map
         (fn [subform]
           (let [x         (first subform)
                 predicate (-> x meta :predicate)
                 wrap-pred (fn [form]
                             (if (nil? predicate)
                               form
                               `(if ~predicate
                                  ~form
                                  ~g)))]
             (if (and (coll? subform)
                      (transformation? x))
               (rewrite-transformation g subform)
               (wrap-pred
                 (cond
                   (keyword? x)            `(rget ~g ~x)
                   (= 'get (fn-name x))    `(p-> ~g ~(second x))
                   (= 'get-in (fn-name x)) `(p-> ~g ~@(second x))
                   :else                   `(-> ~g ~x)))))))))


(defmacro p-> [x & forms]
  (let [g (gensym)]
    `(clojure.core/as-> ~x ~g
       ~@(rewrite-forms g forms))))


(defmacro pcond-> [expr & clauses]
  (assert (even? (count clauses)))
  (let [clauses' (->> clauses
                      (partition 2)
                      (map
                        (fn [[test form]]
                          (let [form (if (keyword? form)
                                       `(rget ~form)
                                       form)]
                            (vary-meta
                              form
                              assoc
                              :predicate
                              test)
                            ))))]
    `(p->
       ~expr
       ~@clauses')))



