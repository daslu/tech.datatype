(ns tech.apl.game-of-life
  "https://youtu.be/a9xAKttWgP4"
  (:require [tech.tensor :as tens]
            [tech.datatype :as dtype]
            [tech.datatype.unary-op :as unary-op]
            [tech.datatype.boolean-op :as bool-op]
            [tech.tensor.impl :as tens-impl]
            [tech.datatype.functional :as dtype-fn]
            [clojure.pprint :as pp]))


(defn membership
  [lhs rhs]
  (let [membership-set (set (vec rhs))]
    (bool-op/boolean-unary-reader
     {:datatype :object}
     (bool-op/make-boolean-unary-op
      :object (contains? membership-set arg))
     lhs)))


(defn apl-take
  "Negative numbers mean left-pad.  Positive numbers mean right-pad."
  [item new-shape]
  (let [item-shape (dtype/shape item)
        abs-new-shape (mapv #(Math/abs (int %)) new-shape)
        abs-min-shape (mapv min item-shape abs-new-shape)
        reshape-item (apply tens/select item (map range abs-min-shape))
        retval (tens/new-tensor abs-new-shape :datatype (dtype/get-datatype item))
        copy-item (apply tens/select retval
                         (map (fn [n-elems orig-item]
                                (if (>= orig-item 0)
                                  (range n-elems)
                                  (take-last n-elems (range (- orig-item)))))
                              abs-min-shape
                              new-shape))]
    (dtype/copy! (dtype/->reader reshape-item)
                 (dtype/->writer copy-item))
    retval))

(defn rotate-vertical
  [tens amount]
  (let [n-shape (count (dtype/shape tens))
        offsets (->> (concat (repeat (- n-shape 1) 0)
                             [(- amount)])
                     vec)]
    (tens/rotate tens offsets)))


(defn rotate-horizontal
  [tens amount]
  (let [n-shape (count (dtype/shape tens))
        offsets (->> (concat [(- amount)]
                             (repeat (- n-shape 1) 0))
                     vec)]
    (tens/rotate tens offsets)))


(def range-tens (tens/reshape (vec (range 9)) [3 3]))

(def bool-tens (-> range-tens
                   (membership [1 2 3 4 7])
                   ;;convert to zeros/ones for display
                   (tens/clone :datatype :int8)))

(def take-tens (apl-take bool-tens [5 7]))

(def right-rotate (rotate-vertical take-tens -2))

(def down-rotate (rotate-horizontal right-rotate -1))

(def R-matrix down-rotate)

(def rotate-arg [1 0 -1])

(def group-rotated (->> rotate-arg
                        (map (partial rotate-vertical down-rotate))))

(def table-rotated (->> rotate-arg
                        (mapcat (fn [rot-amount]
                                  (->> group-rotated
                                       (mapv #(rotate-horizontal
                                               % rot-amount)))))))


(def summed (apply dtype-fn/+ table-rotated))


(def next-gen (-> (bool-op/boolean-unary-reader
                   {}
                   (bool-op/make-boolean-unary-op :int8
                                                  (or (= 3 arg)
                                                      (= 4 arg)))
                   summed)
                  (tens/clone :datatype :int8)))


(defn life
  [R]
  (->> (for [horz-amount rotate-arg
             vert-amount rotate-arg]
         (-> R
             (rotate-vertical vert-amount)
             (rotate-horizontal horz-amount)))
       (apply dtype-fn/+)
       (bool-op/boolean-unary-reader
        {}
        (bool-op/make-boolean-unary-op :int8
                                       (or (= 3 arg)
                                           (= 4 arg))))
       (#(tens/clone % :datatype :int8))))


(defn life-seq
  [R]
  (cons R (lazy-seq (life-seq (life R)))))


(def RR (-> (apl-take R-matrix [-10 -20])
            (apl-take [15 35])))


(defn mat->pic-mat
  [R]
  (->> R
       (unary-op/unary-reader-map
        {:datatype :object}
        (unary-op/make-unary-op :object
                                (if (= 0 (int arg))
                                  (char 0x02DA)
                                  (char 0x2021))))))


(defn print-life-generations
  [& [n-gens]]
  (doseq [life-item (take (or n-gens 1000) (life-seq RR))]
    (println life-item)
    (Thread/sleep 125)))