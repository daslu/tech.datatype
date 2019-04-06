(ns tech.datatype.reader
  (:require [tech.datatype.protocols :as dtype-proto]
            [tech.datatype.casting :as casting]
            [tech.parallel :as parallel]
            [tech.jna :as jna]
            [tech.datatype.nio-access
             :refer [buf-put buf-get
                     datatype->pos-fn
                     datatype->read-fn
                     datatype->write-fn
                     unchecked-full-cast
                     checked-full-read-cast
                     checked-full-write-cast
                     nio-type? list-type?
                     cls-type->read-fn
                     cls-type->write-fn
                     cls-type->pos-fn]]
            [clojure.core.matrix.macros :refer [c-for]]
            [clojure.core.matrix.protocols :as mp]
            [clojure.core.matrix :as m]
            [tech.datatype.typecast :refer :all :as typecast]
            [tech.datatype.fast-copy :as fast-copy]
            [clojure.core.matrix.protocols :as mp]
            ;;Load all iterator bindings
            [tech.datatype.iterator]
            [tech.datatype.argtypes :as argtypes])
  (:import [tech.datatype ObjectReader ObjectReaderIter ObjectIter
            ByteReader ByteReaderIter ByteIter
            ShortReader ShortReaderIter ShortIter
            IntReader IntReaderIter IntIter
            LongReader LongReaderIter LongIter
            FloatReader FloatReaderIter FloatIter
            DoubleReader DoubleReaderIter DoubleIter
            BooleanReader BooleanReaderIter BooleanIter]
           [java.nio Buffer ByteBuffer ShortBuffer
            IntBuffer LongBuffer FloatBuffer DoubleBuffer]
           [it.unimi.dsi.fastutil.bytes ByteList ByteArrayList]
           [it.unimi.dsi.fastutil.shorts ShortList ShortArrayList]
           [it.unimi.dsi.fastutil.ints IntList IntArrayList]
           [it.unimi.dsi.fastutil.longs LongList LongArrayList]
           [it.unimi.dsi.fastutil.floats FloatList FloatArrayList]
           [it.unimi.dsi.fastutil.doubles DoubleList DoubleArrayList]
           [it.unimi.dsi.fastutil.booleans BooleanList BooleanArrayList]
           [it.unimi.dsi.fastutil.objects ObjectList ObjectArrayList]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn ecount
  "Type hinted ecount."
  ^long [item]
  (m/ecount item))


(defmacro make-buffer-reader-impl
  [reader-type buffer-type buffer buffer-pos
   reader-datatype
   intermediate-datatype
   buffer-datatype
   unchecked?]
  `(if ~unchecked?
     (reify
       ~reader-type
       (getDatatype [reader#] ~intermediate-datatype)
       (size [reader#] (int (mp/element-count ~buffer)))
       (read [reader# idx#]
         (-> (cls-type->read-fn ~buffer-type ~buffer-datatype ~buffer idx# ~buffer-pos)
             (unchecked-full-cast ~buffer-datatype ~intermediate-datatype
                                  ~reader-datatype)))
       (iterator [reader#] (reader->iterator reader#))
       (invoke [reader# arg#]
         (.read reader# (int arg#)))
       dtype-proto/PToNioBuffer
       (->buffer-backing-store [reader#]
         (dtype-proto/->buffer-backing-store ~buffer))
       dtype-proto/PToList
       (->list-backing-store [reader#]
         (dtype-proto/->list-backing-store ~buffer))
       dtype-proto/PBuffer
       (sub-buffer [buffer# offset# length#]
         (-> (dtype-proto/sub-buffer ~buffer offset# length#)
             (dtype-proto/->reader-of-type ~intermediate-datatype ~unchecked?)))
       (alias? [buffer# rhs#]
         (dtype-proto/alias? ~buffer rhs#))
       (partially-alias? [lhs# rhs#]
         (dtype-proto/partially-alias? ~buffer rhs#))
       dtype-proto/PSetConstant
       (set-constant! [item# offset# value# elem-count#]
         (dtype-proto/set-constant! ~buffer offset#
                                    (casting/cast value# ~intermediate-datatype)
                                    elem-count#)))
     (reify
       ~reader-type
       (getDatatype [reader#] ~intermediate-datatype)
       (size [reader#] (int (mp/element-count ~buffer)))
       (read [reader# idx#]
         (-> (cls-type->read-fn ~buffer-type ~buffer-datatype ~buffer idx# ~buffer-pos)
             (checked-full-write-cast ~buffer-datatype ~intermediate-datatype
                                      ~reader-datatype)))
       (iterator [reader#] (reader->iterator reader#))
       (invoke [reader# arg#]
         (.read reader# (int arg#)))
       dtype-proto/PToNioBuffer
       (->buffer-backing-store [reader#]
         (dtype-proto/->buffer-backing-store ~buffer))
       dtype-proto/PToNioBuffer
       (->buffer-backing-store [reader#]
         (dtype-proto/->buffer-backing-store ~buffer))
       dtype-proto/PToList
       (->list-backing-store [reader#]
         (dtype-proto/->list-backing-store ~buffer))
       dtype-proto/PBuffer
       (sub-buffer [buffer# offset# length#]
         (-> (dtype-proto/sub-buffer ~buffer offset# length#)
             (dtype-proto/->reader-of-type ~intermediate-datatype ~unchecked?)))
       (alias? [buffer# rhs#]
         (dtype-proto/alias? ~buffer rhs#))
       (partially-alias? [lhs# rhs#]
         (dtype-proto/partially-alias? ~buffer rhs#))
       dtype-proto/PSetConstant
       (set-constant! [item# offset# value# elem-count#]
         (dtype-proto/set-constant! ~buffer offset#
                                    (casting/cast value# ~intermediate-datatype)
                                    elem-count#)))))


(defmacro make-buffer-reader-table
  []
  `(->> [~@(for [dtype casting/numeric-types]
             (let [buffer-datatype (casting/datatype->host-datatype dtype)]
               [[buffer-datatype dtype]
                `(fn [buffer#]
                   (let [buffer# (typecast/datatype->buffer-cast-fn ~buffer-datatype
                                                                    buffer#)
                         buffer-pos# (datatype->pos-fn ~buffer-datatype buffer#)]
                     (make-buffer-reader-impl
                      ~(typecast/datatype->reader-type dtype)
                      ~(typecast/datatype->buffer-type buffer-datatype)
                      buffer# buffer-pos#
                      ~(casting/datatype->safe-host-type dtype) ~dtype
                      ~buffer-datatype
                      true)))]))]
        (into {})))


(def buffer-reader-table (make-buffer-reader-table))


(defn make-buffer-reader
  [item]
  (let [nio-buffer (dtype-proto/->buffer-backing-store item)
        item-dtype (dtype-proto/get-datatype item)
        buffer-dtype (dtype-proto/get-datatype nio-buffer)
        buffer-reader-fn (get buffer-reader-table [buffer-dtype item-dtype])]

    (buffer-reader-fn nio-buffer)))


(defmacro make-list-reader-table
  []
  `(->> [~@(for [dtype casting/base-datatypes]
             (let [buffer-datatype (casting/datatype->host-datatype dtype)]
               [[buffer-datatype dtype]
                `(fn [buffer#]
                   (let [buffer# (typecast/datatype->list-cast-fn
                                  ~buffer-datatype buffer#)]
                     (make-buffer-reader-impl
                      ~(typecast/datatype->reader-type dtype)
                      ~(typecast/datatype->list-type buffer-datatype)
                      buffer# 0
                      ~(casting/datatype->safe-host-type dtype) ~dtype
                      ~buffer-datatype
                      true)))]))]
        (into {})))


(def list-reader-table (make-list-reader-table))


(defn make-list-reader
  [item]
  (let [list-buffer (dtype-proto/->list-backing-store item)
        item-dtype (casting/flatten-datatype (dtype-proto/get-datatype item))
        buffer-dtype (dtype-proto/get-datatype list-buffer)
        list-reader-fn (or (get list-reader-table
                                  [buffer-dtype (casting/flatten-datatype item-dtype)])
                             (get buffer-reader-table [buffer-dtype buffer-dtype]))]
    (list-reader-fn list-buffer)))


(defn- make-object-wrapper
  [reader datatype]
  (let [item-dtype (dtype-proto/get-datatype reader)]
    (when-not (and (= :object (casting/flatten-datatype item-dtype))
                   (= :object (casting/flatten-datatype datatype)))
      (throw (ex-info "Incorrect use of object wrapper" {}))))
  (if (= datatype (dtype-proto/get-datatype reader))
    reader
    (let [obj-reader (typecast/datatype->reader :object reader)]
      (reify
        ObjectReader
        (getDatatype [_] datatype)
        (size [_] (.size obj-reader))
        (read [_ idx] (.read obj-reader idx))
        (iterator [_] (.iterator obj-reader))
        (invoke [item idx] (.read item idx))
        dtype-proto/PToNioBuffer
        (->buffer-backing-store [reader]
          (dtype-proto/->buffer-backing-store obj-reader))
       dtype-proto/PToList
       (->list-backing-store [reader]
         (dtype-proto/->list-backing-store obj-reader))
       dtype-proto/PBuffer
       (sub-buffer [reader offset length]
         (-> (dtype-proto/sub-buffer obj-reader offset length)
             (dtype-proto/->reader-of-type datatype true)))
       (alias? [reader rhs]
         (dtype-proto/alias? obj-reader rhs))
       (partially-alias? [reader rhs]
         (dtype-proto/partially-alias? obj-reader rhs))))))



(defmacro make-marshalling-reader
  [src-reader src-dtype intermediate-dtype result-dtype dst-reader-type unchecked?]
  `(if ~unchecked?
     (reify ~dst-reader-type
       (getDatatype [reader#] ~intermediate-dtype)
       (size [reader#] (.size ~src-reader))
       (read [item# idx#]
         (-> (.read ~src-reader idx#)
             (unchecked-full-cast ~src-dtype ~intermediate-dtype ~result-dtype)))
       (iterator [reader#] (reader->iterator reader#))
       (invoke [reader# arg#]
         (.read reader# (int arg#)))
       dtype-proto/PToNioBuffer
       (->buffer-backing-store [reader#]
         (dtype-proto/->buffer-backing-store ~src-reader))
       dtype-proto/PToList
       (->list-backing-store [reader#]
         (dtype-proto/->list-backing-store ~src-reader))
       dtype-proto/PBuffer
       (sub-buffer [reader# offset# length#]
         (-> (dtype-proto/sub-buffer ~src-reader offset# length#)
             (dtype-proto/->reader-of-type ~intermediate-dtype ~unchecked?)))
       (alias? [reader# rhs#]
         (dtype-proto/alias? ~src-reader rhs#))
       (partially-alias? [reader# rhs#]
         (dtype-proto/partially-alias? ~src-reader rhs#)))

     (reify ~dst-reader-type
       (getDatatype [reader#] ~intermediate-dtype)
       (size [reader#] (.size ~src-reader))
       (read [item# idx#]
         (-> (.read ~src-reader idx#)
             (checked-full-write-cast ~src-dtype ~intermediate-dtype ~result-dtype)))
       (iterator [reader#] (reader->iterator reader#))
       (invoke [reader# arg#]
         (.read reader# (int arg#)))
       dtype-proto/PToNioBuffer
       (->buffer-backing-store [reader#]
         (dtype-proto/->buffer-backing-store ~src-reader))
       dtype-proto/PToList
       (->list-backing-store [reader#]
         (dtype-proto/->list-backing-store ~src-reader))
       dtype-proto/PBuffer
       (sub-buffer [reader# offset# length#]
         (-> (dtype-proto/sub-buffer ~src-reader offset# length#)
             (dtype-proto/->reader-of-type ~intermediate-dtype ~unchecked?)))
       (alias? [reader# rhs#]
         (dtype-proto/alias? ~src-reader rhs#))
       (partially-alias? [reader# rhs#]
         (dtype-proto/partially-alias? ~src-reader rhs#)))))


(defmacro make-marshalling-reader-table
  []
  `(->> [~@(for [dtype (casting/all-datatypes)
                 src-reader-datatype casting/all-host-datatypes]
            [[src-reader-datatype dtype]
             `(fn [src-reader# unchecked?#]
                (let [src-reader# (typecast/datatype->reader ~src-reader-datatype
                                                             src-reader# true)]
                  (make-marshalling-reader
                   src-reader#
                   ~src-reader-datatype
                   ~dtype
                   ~(casting/datatype->safe-host-type dtype)
                   ~(typecast/datatype->reader-type
                     (casting/datatype->safe-host-type dtype))
                   unchecked?#)))])]
        (into {})))



(def marshalling-reader-table (make-marshalling-reader-table))


(defn make-marshalling-reader
  [src-reader dest-dtype unchecked?]
  (let [src-dtype (dtype-proto/get-datatype src-reader)]
    (if (= src-dtype dest-dtype)
      src-reader
      (let [src-reader (if (= (casting/flatten-datatype src-dtype)
                              (casting/flatten-datatype dest-dtype))
                         src-reader
                         (let [reader-fn (get marshalling-reader-table
                                              [(casting/flatten-datatype
                                                (casting/datatype->safe-host-type
                                                 src-dtype))
                                               (casting/flatten-datatype dest-dtype)])]
                           (reader-fn src-reader unchecked?)))
            src-dtype (dtype-proto/get-datatype src-reader)]
        (if (not= src-dtype dest-dtype)
          (make-object-wrapper src-reader dest-dtype)
          src-reader)))))


(defmacro make-sub-buffer-reader
  [reader-type src-reader offset length]
  `(let [offset# (int ~offset)
         length# (int ~length)
         end-elem# (+ offset# length#)]
     (when-not (>= (.size ~src-reader)
                   end-elem#)
       (throw (ex-info (format "requested length out of range (%s > %s)"
                               end-elem# (.size ~src-reader))
                       {:offset offset#
                        :length length#
                        :end-element end-elem#
                        :src-ecount (.size ~src-reader)})))
     (reify ~reader-type
       (getDatatype [reader#] (.getDatatype ~src-reader))
       (size [reader#] length#)
       (read [reader# idx#]
         (when-not (< idx# length#)
           (throw (ex-info (format "Index out of range: %s > %s" idx# length#)
                           {})))
         (.read ~src-reader (+ idx# offset#)))
       (iterator [reader#] (typecast/reader->iterator reader#))
       (invoke [reader# arg#]
         (.read reader# (int arg#))))))


(defmacro extend-reader-type
  [reader-type datatype]
  `(clojure.core/extend
       ~reader-type
     dtype-proto/PToIterable
     {:->iterable-of-type
      (fn [item# dtype# unchecked?#]
        (dtype-proto/->reader-of-type item# dtype# unchecked?#))}
     dtype-proto/PToReader
     {:->reader-of-type
      (fn [item# dtype# unchecked?#]
        (make-marshalling-reader item# dtype# unchecked?#))}
     dtype-proto/PBuffer
     {:sub-buffer (fn [item# offset# length#]
                    (let [item# (typecast/datatype->reader ~datatype item# true)]
                      (if (and (= offset# 0)
                               (= length# (.size item#)))
                        item#
                        (make-sub-buffer-reader
                         ~reader-type
                         (typecast/datatype->reader ~datatype item#)
                         offset# length#))))
      :alias? (fn [lhs# rhs#] (identical? lhs# rhs#))
      :partially-alias? (fn [lhs# rhs#]
                          (dtype-proto/alias? lhs# rhs#))}))


(extend-reader-type ByteReader :int8)
(extend-reader-type ShortReader :int16)
(extend-reader-type IntReader :int32)
(extend-reader-type LongReader :int64)
(extend-reader-type FloatReader :float32)
(extend-reader-type DoubleReader :float64)
(extend-reader-type BooleanReader :boolean)
(extend-reader-type ObjectReader :object)


(defmacro make-const-reader
  [datatype]
  `(fn [item# num-elems#]
     (let [num-elems# (int (or num-elems# Integer/MAX_VALUE))
           item# (checked-full-write-cast
                  item# :unknown ~datatype
                  ~(casting/datatype->safe-host-type datatype))]
       (reify ~(typecast/datatype->reader-type datatype)
         (getDatatype [reader#] ~datatype)
         (size [reader#] num-elems#)
         (read [reader# idx#] item#)
         (iterator [reader#] (typecast/reader->iterator reader#))
         (invoke [reader# arg#]
           (.read reader# (int arg#)))))))

(defmacro make-const-reader-table
  []
  `(->> [~@(for [dtype casting/base-datatypes]
             [dtype `(make-const-reader ~dtype)])]
        (into {})))


(def const-reader-table (make-const-reader-table))


(defn make-const-reader
  [item datatype & [num-elems]]
  (if-let [reader-fn (get const-reader-table (casting/flatten-datatype datatype))]
    (reader-fn item num-elems)
    (throw (ex-info (format "Failed to find reader for datatype %s" datatype) {}))))


(defmacro make-indexed-reader-impl
  [datatype reader-type indexes values unchecked?]
  `(let [idx-reader# (datatype->reader :int32 ~indexes true)
         values# (datatype->reader ~datatype ~values ~unchecked?)]
     (reify ~reader-type
       (getDatatype [item#] ~datatype)
       (size [item#] (.size idx-reader#))
       (read [item# idx#]
         (.read values# (.read idx-reader# idx#)))
       (iterator [item#] (reader->iterator item#))
       (invoke [item# idx#] (.read item# (int idx#))))))


(defmacro make-indexed-reader-creators
  []
  `(->> [~@(for [dtype casting/base-datatypes]
             [dtype `(fn [indexes# values# unchecked?#]
                       (make-indexed-reader-impl
                        ~dtype ~(typecast/datatype->reader-type dtype)
                        indexes# values# unchecked?#))])]
        (into {})))

(def indexed-reader-creators (make-indexed-reader-creators))


(defn make-indexed-reader
  [indexes values {:keys [datatype unchecked?]}]
  (let [datatype (or datatype (dtype-proto/get-datatype values))
        reader-fn (get indexed-reader-creators (casting/flatten-datatype datatype))]
    (reader-fn indexes values unchecked?)))


;;Maybe values is random-read but the indexes are a large sequence
;;In this case we need the indexes to be an iterator.
(defmacro make-indexed-iterable
  [datatype indexes values unchecked?]
  `(let [values# (datatype->reader ~datatype ~values ~unchecked?)]
     (reify
       Iterable
       (iterator [item#]
         (let [idx-iter# (datatype->iter :int32 ~indexes true)]
           (reify ~(typecast/datatype->iter-type datatype)
             (getDatatype [item#] ~datatype)
             (hasNext [item#] (.hasNext idx-iter#))
             (~(datatype->iter-next-fn-name datatype)
              [item#]
              (let [next-idx# (.nextInt idx-iter#)]
                (.read values# next-idx#)))
             (current [item#]
               (.read values# (.current idx-iter#))))))
       dtype-proto/PDatatype
       (get-datatype [item#] ~datatype))))


(defmacro make-indexed-iterable-creators
  []
  `(->> [~@(for [dtype casting/base-datatypes]
             [dtype `(fn [indexes# values# unchecked?#]
                       (make-indexed-iterable
                        ~dtype
                        indexes# values# unchecked?#))])]
        (into {})))


(def indexed-iterable-table (make-indexed-iterable-creators))


(defn make-iterable-indexed-iterable
  [indexes values {:keys [datatype unchecked?]}]
  (let [datatype (or datatype (dtype-proto/get-datatype values))
        reader-fn (get indexed-iterable-table (casting/flatten-datatype datatype))]
    (reader-fn indexes values unchecked?)))


(defmacro make-range-reader
  [datatype]
  (when-not (casting/numeric-type? datatype)
    (throw (ex-info (format "Datatype (%s) is not a numeric type" ~datatype) {})))
  `(fn [start# end# increment#]
     (let [start# (casting/datatype->cast-fn :unknown ~datatype start#)
           end# (casting/datatype->cast-fn :unknown ~datatype end#)
           increment# (casting/datatype->cast-fn :unkown ~datatype increment#)
           n-elems# (int (/ (- end# start#)
                            increment#))]

       (reify ~(typecast/datatype->reader-type datatype)
         (getDatatype [item#] ~datatype)
         (size [item#] n-elems#)
         (read [item# idx#]
           (when-not (< idx# n-elems#)
             (throw (ex-info (format "Index out of range: %s >= %s" idx# n-elems#))))
           (casting/datatype->unchecked-cast-fn
            :unknown ~(casting/datatype->safe-host-type datatype)
            (+ (* increment# idx#)
               start#)))
         (iterator [item#] (typecast/reader->iterator item#))
         (invoke [item# idx#]
           (.read item# (int idx#)))))))


(defmacro make-range-reader-table
  []
  `(->> [~@(for [dtype casting/numeric-types]
             [dtype `(make-range-reader ~dtype)])]
        (into {})))


(def range-reader-table (make-range-reader-table))

(defn reader-range
  [datatype start end & [increment]]
  (if-let [reader-fn (get range-reader-table datatype)]
    (reader-fn start end (or increment 1))
    (throw (ex-info (format "Failed to find reader fn for datatype %s" datatype)
                    {}))))


(defmacro make-reverse-reader
  [datatype]
  `(fn [src-reader#]
     (let [src-reader# (typecast/datatype->reader ~datatype src-reader#)
           n-elems# (.size src-reader#)
           n-elems-m1# (- n-elems# 1)]
       (reify
         ~(typecast/datatype->reader-type datatype)
         (getDatatype [reader#] (dtype-proto/get-datatype src-reader#))
         (size [reader#] (.size src-reader#))
         (read [reader# idx#] (.read src-reader# (- n-elems-m1# idx#)))
         (invoke [reader# idx#]
           (.read reader# (int idx#)))
         (iterator [reader#] (typecast/reader->iterator reader#))))))

(defmacro make-reverse-reader-table
  []
  `(->> [~@(for [dtype casting/base-host-datatypes]
             [dtype `(make-reverse-reader ~dtype)])]
        (into {})))


(def reverse-reader-table (make-reverse-reader-table))


(defn reverse-reader
  [src-reader {:keys [datatype]}]
  (let [datatype (or datatype (dtype-proto/safe-get-datatype src-reader))
        create-fn (get reverse-reader-table (casting/safe-flatten datatype))]
    (create-fn src-reader)))


(defmacro typed-read
  [datatype item idx]
  `(.read (typecast/datatype->reader ~datatype ~item)
          ~idx))
