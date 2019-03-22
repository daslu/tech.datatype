(ns tech.datatype.fast-copy
  (:require [tech.datatype.typecast :refer :all]
            [tech.datatype.protocols :as dtype-proto]
            [tech.jna :as jna]
            [tech.datatype.casting :as casting]
            [clojure.core.matrix.protocols :as mp]
            [tech.datatype.typecast :as typecast]
            [tech.parallel :as parallel]
            [tech.datatype.nio-access :refer [buf-put]]
            [clojure.core.matrix.macros :refer [c-for]])
  (:import  [com.sun.jna Pointer]
            [it.unimi.dsi.fastutil.bytes ByteList ByteArrayList]
            [it.unimi.dsi.fastutil.shorts ShortList ShortArrayList]
            [it.unimi.dsi.fastutil.ints IntList IntArrayList]
            [it.unimi.dsi.fastutil.longs LongList LongArrayList]
            [it.unimi.dsi.fastutil.floats FloatList FloatArrayList]
            [it.unimi.dsi.fastutil.doubles DoubleList DoubleArrayList]
            [it.unimi.dsi.fastutil.booleans BooleanList BooleanArrayList]
            [it.unimi.dsi.fastutil.objects ObjectList ObjectArrayList]
            [tech.datatype
             ObjectReader ObjectWriter
             ByteReader ByteWriter
             ShortReader ShortWriter
             IntReader IntWriter
             LongReader LongWriter
             FloatReader FloatWriter
             DoubleReader DoubleWriter
             BooleanReader BooleanWriter
             ]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(jna/def-jna-fn "c" memcpy
  "Copy bytes from one object to another"
  Pointer
  [dst ensure-ptr-like]
  [src ensure-ptr-like]
  [n-bytes int])


(defmacro parallel-slow-copy
  [datatype dst src unchecked?]
  `(let [src-reader# (typecast/datatype->reader ~datatype ~src ~unchecked?)
         dst-writer# (typecast/datatype->writer ~datatype ~dst ~unchecked?)
         n-elems# (int (mp/element-count ~dst))]
     (parallel/parallel-for
      idx# n-elems#
      (.write dst-writer# idx# (.read src-reader# idx#)))))


(defn parallel-slow-copy!
  [dst src unchecked?]
  (case (dtype-proto/get-datatype dst)
    :int8 (parallel-slow-copy :int8 dst src unchecked?)
    :uint8 (parallel-slow-copy :uint8 dst src unchecked?)
    :int16 (parallel-slow-copy :int16 dst src unchecked?)
    :uint16 (parallel-slow-copy :uint16 dst src unchecked?)
    :int32 (parallel-slow-copy :int32 dst src unchecked?)
    :uint32 (parallel-slow-copy :uint32 dst src unchecked?)
    :int64 (parallel-slow-copy :int64 dst src unchecked?)
    :uint64 (parallel-slow-copy :uint64 dst src unchecked?)
    :float32 (parallel-slow-copy :float32 dst src unchecked?)
    :float64 (parallel-slow-copy :float64 dst src unchecked?)
    :boolean (parallel-slow-copy :boolean dst src unchecked?)
    (parallel-slow-copy :object dst src unchecked?))
  dst)


(defmacro serial-slow-copy
  [datatype dst src unchecked?]
  `(let [src-reader# (typecast/datatype->reader ~datatype ~src ~unchecked?)
         dst-writer# (typecast/datatype->writer ~datatype ~dst ~unchecked?)
         n-elems# (int (mp/element-count ~dst))]
     (c-for [idx# (int 0) (< idx# n-elems#) (unchecked-inc idx#)]
            (.write dst-writer# idx# (.read src-reader# idx#)))))


(defn serial-slow-copy!
  [dst src unchecked?]
  (case (dtype-proto/get-datatype dst)
    :int8 (serial-slow-copy :int8 dst src unchecked?)
    :uint8 (serial-slow-copy :uint8 dst src unchecked?)
    :int16 (serial-slow-copy :int16 dst src unchecked?)
    :uint16 (serial-slow-copy :uint16 dst src unchecked?)
    :int32 (serial-slow-copy :int32 dst src unchecked?)
    :uint32 (serial-slow-copy :uint32 dst src unchecked?)
    :int64 (serial-slow-copy :int64 dst src unchecked?)
    :uint64 (serial-slow-copy :uint64 dst src unchecked?)
    :float32 (serial-slow-copy :float32 dst src unchecked?)
    :float64 (serial-slow-copy :float64 dst src unchecked?)
    :boolean (serial-slow-copy :boolean dst src unchecked?)
    (serial-slow-copy :object dst src unchecked?))
  dst)


(defmacro impl-nio-write
  [datatype dst src unchecked?]
  `(let [src-reader# (typecast/datatype->reader ~datatype ~src ~unchecked?)
         dst-buf# (typecast/datatype->buffer-cast-fn ~datatype ~src)
         dst-pos# (.position dst-buf#)
         n-elems# (int (mp/element-count ~dst))]
     (parallel/parallel-for
      idx# n-elems#
      (buf-put dst-buf# idx# dst-pos# (.read src-reader# idx#)))))


(defn parallel-nio-write!
  [dst src unchecked?]
  (case (dtype-proto/get-datatype dst)
    :int8 (impl-nio-write :int8 dst src unchecked?)
    :int16 (impl-nio-write :int16 dst src unchecked?)
    :int32 (impl-nio-write :int32 dst src unchecked?)
    :int64 (impl-nio-write :int64 dst src unchecked?)
    :float32 (impl-nio-write :float32 dst src unchecked?)
    :float64 (impl-nio-write :float64 dst src unchecked?)))


(defmacro impl-list-write
  [datatype dst src unchecked?]
  `(let [src-reader# (typecast/datatype->reader ~datatype ~src ~unchecked?)
         dst-buf# (typecast/datatype->list-cast-fn ~datatype ~src)
         n-elems# (int (mp/element-count ~dst))]
     (parallel/parallel-for
      idx# n-elems#
      (.set dst-buf# idx# (.read src-reader# idx#)))))


(defn parallel-list-write!
  [dst src unchecked?]
  (case (dtype-proto/get-datatype dst)
    :int8 (impl-list-write :int8 dst src unchecked?)
    :int16 (impl-list-write :int16 dst src unchecked?)
    :int32 (impl-list-write :int32 dst src unchecked?)
    :int64 (impl-list-write :int64 dst src unchecked?)
    :float32 (impl-list-write :float32 dst src unchecked?)
    :float64 (impl-list-write :float64 dst src unchecked?)
    :boolean (impl-list-write :boolean dst src unchecked?)
    (impl-list-write :object dst src unchecked?)))


(defn copy!
  "Copy defined when both things are convertible to concrete types, and the types
  of those concrete types exactly match."
  [dst src]
  (let [dst-ptr (as-ptr dst)
        dst-ary (as-array dst)
        src-ptr (as-ptr src)
        src-ary (as-array src)
        src-buf (as-nio-buffer src)
        dst-buf (as-nio-buffer dst)
        src-list (as-list src)
        dst-list (as-list dst)
        _ (when-not (and (or src-buf src-list)
                         (or dst-buf dst-list))
            (throw (ex-info "Fast copy called inappropriatedly; convertible to list or nio"
                            {})))
        src-dtype (dtype-proto/get-datatype (or src-buf src-list))
        dst-dtype (dtype-proto/get-datatype (or dst-buf dst-list))
        n-elems (long (mp/element-count dst))]
    (when-not (= src-dtype dst-dtype)
      (throw (ex-info "Fast copy called inappropriately; datatypes do not match"
                      {:src-datatype src-dtype
                       :dst-datatype dst-dtype})))
    (cond
      ;;Very fast path
      (and dst-ptr src-ary)
      (let [{:keys [array-data array-offset]} src-ary
            array-offset (int array-offset)]
        (case src-dtype
          :int8 (.write dst-ptr 0 ^bytes array-data array-offset n-elems)
          :int16 (.write dst-ptr 0 ^shorts array-data array-offset n-elems)
          :int32 (.write dst-ptr 0 ^ints array-data array-offset n-elems)
          :int64 (.write dst-ptr 0 ^longs array-data array-offset n-elems)
          :float32 (.write dst-ptr 0 ^floats array-data array-offset n-elems)
          :float64 (.write dst-ptr 0 ^doubles array-data array-offset n-elems)))
      (and dst-ary src-ptr)
      (let [{:keys [array-data array-offset]} dst-ary
            array-offset (int array-offset)]
        (case src-dtype
          :int8 (.read src-ptr 0 ^bytes array-data array-offset n-elems)
          :int16 (.read src-ptr 0 ^shorts array-data array-offset n-elems)
          :int32 (.read src-ptr 0 ^ints array-data array-offset n-elems)
          :int64 (.read src-ptr 0 ^longs array-data array-offset n-elems)
          :float32 (.read src-ptr 0 ^floats array-data array-offset n-elems)
          :float64 (.read src-ptr 0 ^doubles array-data array-offset n-elems)))
      (and src-buf dst-buf)
      (memcpy dst-buf src-buf (* n-elems (casting/numeric-byte-width src-dtype)))
      (and src-list dst-ary)
      (let [{:keys [array-data array-offset]} dst-ary]
        (case src-dtype
          :int8 (.getElements ^ByteList src-list 0 ^bytes array-data array-offset n-elems)
          :int16 (.getElements ^ShortList src-list 0 ^shorts array-data array-offset n-elems)
          :int32 (.getElements ^IntList src-list 0 ^ints array-data array-offset n-elems)
          :int64 (.getElements ^LongList src-list 0 ^longs array-data array-offset n-elems)
          :float32 (.getElements ^FloatList src-list 0 ^floats array-data array-offset n-elems)
          :float64 (.getElements ^DoubleList src-list 0 ^doubles array-data array-offset n-elems)
          :boolean (.getElements ^BooleanList src-list 0 ^booleans array-data array-offset n-elems)
          (.getElements ^ObjectList src-list 0 ^objects array-data array-offset n-elems)))
      (and src-list dst-list)
      (case src-dtype
          :int8 (.addAll ^ByteList src-list 0 ^ByteList dst-list)
          :int16 (.addAll ^ShortList src-list 0 ^ShortList dst-list)
          :int32 (.addAll ^IntList src-list 0 ^IntList dst-list)
          :int64 (.addAll ^LongList src-list 0 ^LongList dst-list)
          :float32 (.addAll ^FloatList src-list 0 ^FloatList dst-list)
          :float64 (.addAll ^DoubleList src-list 0 ^DoubleList dst-list)
          :boolean (.addAll ^BooleanList src-list 0 ^BooleanList dst-list)
          (.addAll ^ObjectList src-list 0 ^ObjectList dst-list))
      dst-buf
      (parallel-nio-write! dst-buf src)
      dst-list
      (parallel-list-write! dst-list src)
      :else
      (parallel-slow-copy! dst src true)))
  dst)
