(ns tech.datatype.argsort
  (:require [tech.datatype.typecast :as typecast]
            [tech.datatype.casting :as casting]
            [tech.datatype.protocols :as dtype-proto]
            [clojure.core.matrix.protocols :as mp])
  (:import [it.unimi.dsi.fastutil.bytes ByteArrays ByteComparator]
           [it.unimi.dsi.fastutil.shorts ShortArrays ShortComparator]
           [it.unimi.dsi.fastutil.ints IntArrays IntComparator]
           [it.unimi.dsi.fastutil.longs LongArrays LongComparator]
           [it.unimi.dsi.fastutil.floats FloatArrays FloatComparator]
           [it.unimi.dsi.fastutil.doubles DoubleArrays DoubleComparator]
   [tech.datatype
            Comparator$ByteComp
            Comparator$ShortComp
            Comparator$IntComp
            Comparator$LongComp
            Comparator$FloatComp
            Comparator$DoubleComp]))

(defn datatype->comparator-type
  [datatype]
  (case datatype
    :int8 'ByteComparator
    :int16 'ShortComparator
    :int32 'IntComparator
    :int64 'LongComparator
    :float32 'FloatComparator
    :float64 'DoubleComparator))


(defmacro datatype->comparator
  [datatype comparator]
  `(if (instance? ~(resolve (datatype->comparator-type datatype)) ~comparator)
     ~comparator
     (throw (ex-info (format "Comparator is not of correct type: %s" ~comparator) {}))))


(defn int8-comparator ^ByteComparator [item] (datatype->comparator :int8 item))
(defn int16-comparator ^ShortComparator [item] (datatype->comparator :int16 item))
(defn int32-comparator ^IntComparator [item] (datatype->comparator :int32 item))
(defn int64-comparator ^LongComparator [item] (datatype->comparator :int64 item))
(defn float32-comparator ^FloatComparator [item] (datatype->comparator :float32 item))
(defn float64-comparator ^DoubleComparator [item] (datatype->comparator :float64 item))


(defn datatype->tech-comparator-type
  [datatype]
  (case datatype
    :int8 'Comparator$ByteComp
    :int16 'Comparator$ShortComp
    :int32 'Comparator$IntComp
    :int64 'Comparator$LongComp
    :float32 'Comparator$FloatComp
    :float64 'Comparator$DoubleComp))

(defn datatype->tech-comparator-fn-name
  [datatype]
  (case datatype
    :int8 'compareBytes
    :int16 'compareShorts
    :int32 'compareInts
    :int64 'compareLongs
    :float32 'compareFloats
    :float64 'compareDoubles))



(defmacro make-comparator
  [datatype comp-body]
  `(reify
     ~(datatype->tech-comparator-type datatype)
     (~(datatype->tech-comparator-fn-name datatype)
      [item# ~'lhs ~'rhs]
      ~comp-body)))


(defmacro default-compare-fn
  [datatype lhs rhs]
  (case datatype
    :int8 `(Byte/compare ~lhs ~rhs)
    :int16 `(Short/compare ~lhs ~rhs)
    :int32 `(Integer/compare ~lhs ~rhs)
    :int64 `(Long/compare ~lhs ~rhs)
    :float32 `(Float/compare ~lhs ~rhs)
    :float64 `(Double/compare ~lhs ~rhs)))



(defmacro make-argsort
  [datatype]
  `(fn [values# parallel?# reverse?# comparator#]
     (let [comparator# (or comparator#
                           (make-comparator ~datatype
                                            (default-compare-fn ~datatype ~'lhs ~'rhs)))
           n-elems# (int (mp/element-count values#))]
       (if (= n-elems# 0)
         (int-array 0)
         (let [index-array# (int-array (range n-elems#))
               values# (typecast/datatype->reader ~datatype values# true)
               value-comparator# (datatype->comparator ~datatype comparator#)
               idx-comparator# (if reverse?#
                                 (make-comparator :int32 (.compare value-comparator#
                                                                   (.read values# ~'rhs)
                                                                   (.read values# ~'lhs)))
                                 (make-comparator :int32 (.compare value-comparator#
                                                                   (.read values# ~'lhs)
                                                                   (.read values# ~'rhs))))]

           (if parallel?#
             (IntArrays/parallelQuickSort index-array# (int32-comparator idx-comparator#))
             (IntArrays/quickSort index-array# (int32-comparator idx-comparator#)))
           index-array#)))))


(defmacro make-argsort-table
  []
  `(->> [~@(for [dtype casting/host-numeric-types]
             [dtype `(make-argsort ~dtype)])]
        (into {})))


(def argsort-table (make-argsort-table))


(defn argsort
  [values & {:keys [parallel?
                    typed-comparator
                    datatype
                    reverse?]
             :or {parallel? true}}]
  (let [datatype (or datatype (dtype-proto/get-datatype values))
        _ (when-not (casting/numeric-type? datatype)
            (throw (ex-info (format "Datatype is not numeric: %s" datatype))))
        sort-fn (get argsort-table (casting/datatype->safe-host-type datatype))]
    (sort-fn values parallel? reverse? typed-comparator)))
