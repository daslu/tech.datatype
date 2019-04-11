(ns tech.datatype.functional
  (:require [tech.datatype.unary-op :as unary]
            [tech.datatype.binary-op :as binary]
            [tech.datatype.reduce-op :as reduce-op]
            [tech.datatype.iterator :as iterator]
            [tech.datatype.functional.impl :as impl]
            [tech.datatype.argsort :as argsort]
            [tech.datatype.boolean-op :as boolean-op]
            [tech.datatype.binary-search :as binary-search]
            [tech.datatype.protocols :as dtype-proto]
            [tech.datatype.base :as dtype-base]
            [tech.datatype.casting :as casting]
            [tech.datatype.sparse.reader :as sparse-reader])
  (:refer-clojure :exclude [+ - / *
                            <= < >= >
                            identity
                            min max
                            bit-xor bit-and bit-and-not bit-not bit-set bit-test
                            bit-or bit-flip bit-clear
                            bit-shift-left bit-shift-right unsigned-bit-shift-right
                            quot rem cast not and or]))



(def all-builtins (impl/define-all-builtins))


(impl/export-symbols tech.datatype.unary-op
                     make-unary-op
                     make-numeric-unary-op
                     make-float-double-unary-op
                     make-all-datatype-unary-op
                     unary-iterable-map
                     unary-reader-map
                     unary-iterable
                     unary-reader)


(impl/export-symbols tech.datatype.binary-op
                     binary-iterable-map
                     binary-reader-map
                     make-binary-op
                     make-numeric-binary-op
                     make-long-binary-op
                     make-double-binary-op
                     binary-iterable
                     binary-reader)


(impl/export-symbols tech.datatype.boolean-op
                     boolean-unary-iterable-map
                     boolean-unary-reader-map
                     boolean-binary-iterable-map
                     boolean-binary-reader-map
                     make-boolean-binary-op
                     make-boolean-unary-op
                     make-numeric-binary-boolean-op
                     make-all-datatype-binary-boolean-op
                     boolean-unary-iterable
                     boolean-unary-reader
                     boolean-binary-iterable
                     boolean-binary-reader)


(impl/export-symbols tech.datatype.functional.impl
                     argmin argmin-last
                     argmax argmax-last
                     argcompare
                     argcompare-last
                     apply-reduce-op
                     apply-unary-op
                     apply-binary-op
                     apply-unary-boolean-op
                     apply-binary-boolean-op)



(defn argsort
  "Return a list of indexes in sorted-values order.  Values must be
  convertible to a reader.  Sorts least-to-greatest by default
  unless either reverse? is specified or a correctly typed comparator
  is provided.
  Returns an int32 array or indexes."
  [values & {:keys [parallel?
                    typed-comparator
                    datatype
                    reverse?]
             :or {parallel? true}
             :as options}]
  (let [options (impl/default-options (assoc options :parallel? true))]
    (argsort/argsort (sparse-reader/->reader values)
                     (impl/default-options options))))


(defn binary-search
  "Perform a binary search of (convertible to reader) values for target and return a
  tuple of [found? elem-pos-or-insert-pos].  If the element is found, the elem-pos
  contains the index.  If the element is not found, then it contains the index where the
  element would be inserted to maintain sort order of the values."
  [values target & {:as options}]
  (let [options (impl/default-options options)
        datatype (clojure.core/or (:datatype options) (dtype-base/get-datatype target))]
    (binary-search/binary-search
     (sparse-reader/->reader values datatype)
     target options)))


(defn argfilter
  "Returns a (potentially infinite) sequence of indexes that pass the filter."
  [bool-op filter-seq & [second-seq]]
  (if second-seq
    (let [bool-op (if (satisfies? dtype-proto/PToBinaryBooleanOp bool-op)
                    bool-op
                    (boolean-op/make-boolean-binary-op
                     :object (casting/datatype->cast-fn :unknown
                                                        :boolean
                                                        (bool-op x y))))]
      (boolean-op/binary-argfilter (impl/default-options {})
                                   bool-op
                                   (iterator/->iterable filter-seq)
                                   (iterator/->iterable second-seq)))
    (let [bool-op (if (satisfies? dtype-proto/PToUnaryBooleanOp bool-op)
                    bool-op
                    (boolean-op/make-boolean-unary-op
                     :object (boolean (bool-op x))))]
      (boolean-op/unary-argfilter (impl/default-options {})
                                  bool-op
                                  filter-seq))))
