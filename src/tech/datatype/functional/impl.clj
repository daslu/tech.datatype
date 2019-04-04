(ns tech.datatype.functional.impl
  (:require [tech.datatype.unary-op
             :refer [datatype->unary-op]
             :as unary]
            [tech.datatype.binary-op
             :refer [datatype->binary-op]
             :as binary]
            [tech.datatype.reduce-op
             :refer [datatype->reduce-op]
             :as reduce-op]
            [tech.datatype.boolean-op
             :refer [boolean-unary-iterable
                     datatype->boolean-unary
                     boolean-unary-reader
                     boolean-binary-iterable
                     boolean-binary-reader
                     datatype->boolean-binary]
             :as boolean-op]
            [tech.datatype.iterator :as iterator]
            [tech.datatype.argtypes :as argtypes]
            [tech.datatype.base :as dtype-base]
            [tech.datatype.reader :as reader]
            [tech.datatype.protocols :as dtype-proto]
            [tech.datatype.casting :as casting]
            [tech.datatype.array]
            [tech.datatype.list]
            [tech.datatype.primitive]
            [tech.datatype.sparse.reader :as sparse-reader]))

(def ^:dynamic *datatype* nil)
(def ^:dynamic *unchecked?* nil)

(defn default-options
  [options]
  (merge options
         (when *datatype*
           {:datatype *datatype*})
         (when *unchecked?*
           {:unchecked? *unchecked?*})))


(defn apply-reduce-op
  "Reduce an iterable into one thing.  This is not currently parallelized."
  [{:keys [datatype unchecked?] :as options} reduce-op values]
  (let [datatype (or datatype (dtype-base/get-datatype values))]
    (if (= (argtypes/arg->arg-type values) :scalar)
      (case (casting/safe-flatten datatype)
        :int8 (.finalize (datatype->reduce-op :int8 reduce-op unchecked?)
                         values 1)
        :int16 (.finalize (datatype->reduce-op :int16 reduce-op unchecked?)
                          values 1)
        :int32 (.finalize (datatype->reduce-op :int32 reduce-op unchecked?)
                          values 1)
        :int64 (.finalize (datatype->reduce-op :int64 reduce-op unchecked?)
                          values 1)
        :float32 (.finalize (datatype->reduce-op :float32 reduce-op unchecked?)
                            values 1)
        :float64 (.finalize (datatype->reduce-op :float64 reduce-op unchecked?)
                            values 1)
        :boolean (.finalize (datatype->reduce-op :boolean reduce-op unchecked?)
                            values 1)
        :object (.finalize (datatype->reduce-op :object reduce-op unchecked?)
                           values 1))
      (reduce-op/iterable-reduce options reduce-op values))))


(defn apply-unary-op
    "Perform operation returning a scalar, reader, or an iterator.  Note that the
  results of this could be a reader, iterable or a scalar depending on what was passed
  in.  Also note that the results are lazyily calculated so no computation is done in
  this method aside from building the next thing *unless* the inputs are scalar in which
  case the operation is evaluated immediately."
  [{:keys [datatype unchecked?] :as options} un-op arg]
  (case (argtypes/arg->arg-type arg)
    :reader
    (unary/unary-reader-map options un-op arg)
    :iterable
    (unary/unary-iterable-map options un-op arg)
    :scalar
    (let [datatype (or datatype (dtype-base/get-datatype arg))]
      (if (= :identity (dtype-proto/op-name un-op))
        (if unchecked?
          (casting/unchecked-cast arg datatype)
          (casting/cast arg datatype)))
      (case (casting/safe-flatten datatype)
        :int8 (.op (datatype->unary-op :int8 un-op unchecked?) arg)
        :int16 (.op (datatype->unary-op :int16 un-op unchecked?) arg)
        :int32 (.op (datatype->unary-op :int32 un-op unchecked?) arg)
        :int64 (.op (datatype->unary-op :int64 un-op unchecked?) arg)
        :float32 (.op (datatype->unary-op :float32 un-op unchecked?) arg)
        :float64 (.op (datatype->unary-op :float64 un-op unchecked?) arg)
        :boolean (.op (datatype->unary-op :boolean un-op unchecked?) arg)
        :object (.op (datatype->unary-op :object un-op unchecked?) arg)))))


(defn apply-binary-op
  "We perform a left-to-right reduction making scalars/readers/etc.  This matches
  clojure semantics.  Note that the results of this could be a reader, iterable or a
  scalar depending on what was passed in.  Also note that the results are lazily
  calculated so no computation is done in this method aside from building the next thing
  *unless* the inputs are scalar in which case the operation is evaluated immediately."
  [{:keys [datatype unchecked?] :as options}
   bin-op arg1 arg2 & args]
  (let [all-args (concat [arg1 arg2] args)
        all-arg-types (->> all-args
                           (map argtypes/arg->arg-type)
                           set)
        op-arg-type (cond
                      (all-arg-types :iterable)
                      :iterable
                      (all-arg-types :reader)
                      :reader
                      :else
                      :scalar)
        datatype (or datatype (dtype-base/get-datatype arg1))
        n-elems (long (if (= op-arg-type :reader)
                        (->> all-args
                             (remove #(= :scalar (argtypes/arg->arg-type %)))
                             (map dtype-base/ecount)
                             (apply min))
                        Integer/MAX_VALUE))]
    (loop [arg1 arg1
           arg2 arg2
           args args]
      (let [arg1-type (argtypes/arg->arg-type arg1)
            arg2-type (argtypes/arg->arg-type arg2)
            op-map-fn (case op-arg-type
                        :iterable
                        (partial binary/binary-iterable-map
                                 (assoc options :datatype datatype)
                                 bin-op)
                        :reader
                        (partial binary/binary-reader-map
                                 (assoc options :datatype datatype)
                                 bin-op)
                        :scalar
                        nil)
            un-map-fn (case op-arg-type
                        :iterable
                        (partial unary/unary-iterable-map
                                 (assoc options :datatype datatype))
                        :reader
                        (partial unary/unary-reader-map
                                 (assoc options :datatype datatype))
                        :scalar
                        nil)
            arg-result
            (cond
              (and (= arg1-type :scalar)
                   (= arg2-type :scalar))
              (case (casting/safe-flatten datatype)
                :int8 (.op (datatype->binary-op :int8 bin-op unchecked?) arg1 arg2)
                :int16 (.op (datatype->binary-op :int16 bin-op unchecked?) arg1 arg2)
                :int32 (.op (datatype->binary-op :int32 bin-op unchecked?) arg1 arg2)
                :int64 (.op (datatype->binary-op :int64 bin-op unchecked?) arg1 arg2)
                :float32 (.op (datatype->binary-op :float32 bin-op unchecked?)
                              arg1 arg2)
                :float64 (.op (datatype->binary-op :float64 bin-op unchecked?)
                              arg1 arg2)
                :boolean (.op (datatype->binary-op :boolean bin-op unchecked?)
                              arg1 arg2)
                :object (.op (datatype->binary-op :object bin-op unchecked?)
                             arg1 arg2))
              (= arg1-type :scalar)
              (un-map-fn (binary/binary->unary {:datatype datatype
                                                :left-associate? true}
                                               bin-op arg1)
                         arg2)
              (= arg2-type :scalar)
              (un-map-fn (binary/binary->unary {:datatype datatype}
                                               bin-op arg2)
                         arg1)
              :else
              (op-map-fn arg1 arg2))]
        (if (first args)
          (recur arg-result (first args) (rest args))
          arg-result)))))


(defn apply-unary-boolean-op
    "Perform operation returning a scalar, reader, or an iterator.  Note that the
  results of this could be a reader, iterable or a scalar depending on what was passed
  in.  Also note that the results are lazyily calculated so no computation is done in
  this method aside from building the next thing *unless* the inputs are scalar in which
  case the operation is evaluated immediately."
  [{:keys [datatype unchecked?] :as options} un-op arg]
  (case (argtypes/arg->arg-type arg)
    :reader
    (boolean-unary-reader options un-op arg)
    :iterable
    (boolean-unary-iterable options un-op arg)
    :scalar
    (let [datatype (or datatype (dtype-base/get-datatype arg))]
      (if (= :no-op un-op)
        (if unchecked?
          (casting/unchecked-cast arg datatype)
          (casting/cast arg datatype)))
      (case (casting/safe-flatten datatype)
        :int8 (.op (datatype->boolean-unary :int8 un-op unchecked?) arg)
        :int16 (.op (datatype->boolean-unary :int16 un-op unchecked?) arg)
        :int32 (.op (datatype->boolean-unary :int32 un-op unchecked?) arg)
        :int64 (.op (datatype->boolean-unary :int64 un-op unchecked?) arg)
        :float32 (.op (datatype->boolean-unary :float32 un-op unchecked?) arg)
        :float64 (.op (datatype->boolean-unary :float64 un-op unchecked?) arg)
        :boolean (.op (datatype->boolean-unary :boolean un-op unchecked?) arg)
        :object (.op (datatype->boolean-unary :object un-op unchecked?) arg)))))


(defn apply-binary-boolean-op
  "We perform a left-to-right reduction making scalars/readers/etc.  This matches
  clojure semantics.  Note that the results of this could be a reader, iterable or a
  scalar depending on what was passed in.  Also note that the results are lazily
  calculated so no computation is done in this method aside from building the next thing
  *unless* the inputs are scalar in which case the operation is evaluated immediately."
  [{:keys [datatype unchecked?] :as options}
   bin-op arg1 arg2 & args]
  (let [all-args (concat [arg1 arg2] args)
        all-arg-types (->> all-args
                           (map argtypes/arg->arg-type)
                           set)
        op-arg-type (cond
                      (all-arg-types :iterable)
                      :iterable
                      (all-arg-types :reader)
                      :reader
                      :else
                      :scalar)
        datatype (or datatype (dtype-base/get-datatype arg1))
        n-elems (long (if (= op-arg-type :reader)
                        (->> all-args
                             (remove #(= :scalar (argtypes/arg->arg-type %)))
                             (map dtype-base/ecount)
                             (apply min))
                        Integer/MAX_VALUE))]
    (loop [arg1 arg1
           arg2 arg2
           args args]
      (let [arg1-type (argtypes/arg->arg-type arg1)
            arg2-type (argtypes/arg->arg-type arg2)
            op-map-fn (case op-arg-type
                        :iterable
                        (partial boolean-binary-iterable
                                 (assoc options :datatype datatype)
                                 bin-op)
                        :reader
                        (partial boolean-binary-reader
                                 (assoc options :datatype datatype)
                                 bin-op)
                        :scalar
                        nil)
            arg-result
            (cond
              (and (= arg1-type :scalar)
                   (= arg2-type :scalar))
              (case (casting/safe-flatten datatype)
                :int8 (.op (datatype->boolean-binary :int8 bin-op unchecked?)
                           arg1 arg2)
                :int16 (.op (datatype->boolean-binary :int16 bin-op unchecked?)
                            arg1 arg2)
                :int32 (.op (datatype->boolean-binary :int32 bin-op unchecked?)
                            arg1 arg2)
                :int64 (.op (datatype->boolean-binary :int64 bin-op unchecked?)
                            arg1 arg2)
                :float32 (.op (datatype->boolean-binary :float32 bin-op unchecked?)
                              arg1 arg2)
                :float64 (.op (datatype->boolean-binary :float64 bin-op unchecked?)
                              arg1 arg2)
                :boolean (.op (datatype->boolean-binary :boolean bin-op unchecked?)
                              arg1 arg2)
                :object (.op (datatype->boolean-binary :object bin-op unchecked?)
                             arg1 arg2))
              (= arg1-type :scalar)
              (op-map-fn (sparse-reader/const-sparse-reader arg1 datatype) arg2)
              (= arg2-type :scalar)
              (op-map-fn arg1 (sparse-reader/const-sparse-reader arg2 datatype))
              :else
              (op-map-fn arg1 arg2))]
        (if (first args)
          (recur arg-result (first args) (rest args))
          arg-result)))))


(defonce all-builtins (->> (concat (->> unary/builtin-unary-ops
                                        (map (fn [[k v]]
                                               {:name k
                                                :type :unary
                                                :operator v})))
                                   (->> binary/builtin-binary-ops
                                        (map (fn [[k v]]
                                               {:name k
                                                :type :binary
                                                :operator v})))
                                   (->> boolean-op/builtin-boolean-unary-ops
                                        (map (fn [[k v]]
                                               {:name k
                                                :type :boolean-unary
                                                :operator v})))
                                   (->> boolean-op/builtin-boolean-binary-ops
                                        (map (fn [[k v]]
                                               {:name k
                                                :type :boolean-binary
                                                :operator v}))))
                           (group-by :name)))


(defmacro def-builtin-operator
  [op-name op-seq]
  (let [op-types (->> (map :type op-seq)
                      set)
        op-name-symbol (symbol (name op-name))
        type-map (->> op-seq
                      (map (fn [op-item]
                             [(:type op-item) (:operator op-item)]))
                      (into {}))
        argnum-types (->> op-types
                          (map {:unary :unary
                                :boolean-unary :unary
                                :binary :binary
                                :boolean-binary :binary})
                          set)]
    `(defn ~op-name-symbol
       ~(str "Operator " (name op-name) ":" (vec op-types) "." )
       [& ~'args]
       (let [~'n-args (count ~'args)]
         ~(cond
            (= argnum-types #{:unary :binary})
            `(when-not (> ~'n-args 0)
               (throw (ex-info (format "Operator called with too few (%s) arguments."
                                       ~'n-args))))
            (= argnum-types #{:unary})
            `(when-not (= ~'n-args 1)
               (throw (ex-info (format "Operator takes 1 argument, (%s) given."
                                       ~'n-args))))
            (= argnum-types #{:binary})
            `(when-not (> ~'n-args 1)
               (throw (ex-info (format "Operator called with too few (%s) arguments"
                                       ~'n-args))))
            :else
            (throw (ex-info "Incorrect op types" {:types argnum-types
                                                  :op-types op-types})))
         (let [~'datatype (or *datatype*
                              (dtype-base/get-datatype (first ~'args)))
               ~'options {:datatype ~'datatype
                          :unchecked? *unchecked?*}]
           (if (= ~'n-args 1)
             ~(if (contains? op-types :boolean-unary)
                `(apply-unary-boolean-op
                  ~'options
                  (get boolean-op/builtin-boolean-unary-ops
                       ~op-name)
                  (first ~'args))
                `(apply-unary-op
                  ~'options
                  (get unary/builtin-unary-ops ~op-name)
                 (first ~'args)))
             ~(if (contains? op-types :boolean-binary)
                `(apply apply-binary-boolean-op
                        ~'options
                        (get boolean-op/builtin-boolean-binary-ops
                             ~op-name)
                        ~'args)
                `(apply apply-binary-op
                        ~'options
                        (get binary/builtin-binary-ops ~op-name)
                        ~'args))))))))


(defmacro define-all-builtins
  []
  `(do
     ~@(->> all-builtins
            (map (fn [[op-name op-seq]]
                   `(def-builtin-operator ~op-name ~op-seq))))))
