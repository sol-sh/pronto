(ns pronto.reflection
  (:import [clojure.lang Reflector]
           [com.google.protobuf 
            Descriptors$EnumDescriptor]))


(defn enum? [^Class clazz]
  (.isEnum clazz))


(defn enum-values [^Class clazz]
  (let [descriptor (Reflector/invokeStaticMethod clazz "getDescriptor" (to-array nil))]
    (.getValues ^Descriptors$EnumDescriptor descriptor)))
