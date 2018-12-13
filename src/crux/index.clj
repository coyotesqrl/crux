(ns crux.index
  (:require [clojure.tools.logging :as log]
            [clojure.set :as set]
            [crux.byte-utils :as bu]
            [crux.codec :as c]
            [crux.kv :as kv]
            [crux.db :as db]
            [taoensso.nippy :as nippy])
  (:import java.io.Closeable
           [java.util Arrays Collections Comparator Date]
           crux.codec.EntityTx))

(set! *unchecked-math* :warn-on-boxed)

;; Indexes

(defrecord PrefixKvIterator [i ^bytes prefix]
  kv/KvIterator
  (seek [_ k]
    (when-let [k (kv/seek i k)]
      (when (bu/bytes=? k prefix (alength prefix))
        k)))

  (next [_]
    (when-let [k (kv/next i)]
      (when (bu/bytes=? k prefix (alength prefix))
        k)))

  (value [_]
    (kv/value i))

  Closeable
  (close [_]
    (.close ^Closeable i)))

(defn new-prefix-kv-iterator ^java.io.Closeable [i prefix]
  (->PrefixKvIterator i prefix))

;; AVE

(defn- attribute-value+placeholder [k peek-state]
  (let [[value] (c/decode-attribute+value+entity+content-hash-key->value+entity+content-hash k)]
    (reset! peek-state {:last-k k :value value})
    [value :crux.index.binary-placeholder/value]))

(defrecord DocAttributeValueEntityValueIndex [i ^bytes attr-bytes peek-state]
  db/Index
  (seek-values [this k]
    (when-let [k (->> (or k c/empty-byte-array)
                      (c/encode-attribute+value+entity+content-hash-key attr-bytes)
                      (kv/seek i))]
      (attribute-value+placeholder k peek-state)))

  (next-values [this]
    (let [{:keys [^bytes last-k]} @peek-state
          prefix-size (- (alength last-k) c/id-size c/id-size)]
      (when-let [k (some->> (bu/inc-unsigned-bytes! (Arrays/copyOf last-k prefix-size))
                            (kv/seek i))]
        (attribute-value+placeholder k peek-state)))))

(defn new-doc-attribute-value-entity-value-index [snapshot attr]
  (let [attr-bytes (c/id->bytes attr)
        prefix (c/encode-attribute+value+entity+content-hash-key attr-bytes)]
    (->DocAttributeValueEntityValueIndex (new-prefix-kv-iterator (kv/new-iterator snapshot) prefix) attr-bytes (atom nil))))

(defn- attribute-value-entity-entity+value [i ^bytes current-k attr-bytes value entity-as-of-idx peek-state]
  (loop [k current-k]
    (reset! peek-state (bu/inc-unsigned-bytes! (Arrays/copyOf k (- (alength k) c/id-size))))
    (or (let [[_ eid] (c/decode-attribute+value+entity+content-hash-key->value+entity+content-hash k)
              eid-bytes (c/id->bytes eid)
              [_ ^EntityTx entity-tx] (db/seek-values entity-as-of-idx eid-bytes)]
          (when entity-tx
            (let [version-k (c/encode-attribute+value+entity+content-hash-key
                             attr-bytes
                             value
                             eid-bytes
                             (c/id->bytes (.content-hash entity-tx)))]
              (when-let [found-k (kv/seek i version-k)]
                (when (bu/bytes=? version-k found-k)
                  [eid-bytes entity-tx])))))
        (when-let [k (some->> @peek-state (kv/seek i))]
          (recur k)))))

(defn- attribute-value-value+prefix-iterator [i ^DocAttributeValueEntityValueIndex value-entity-value-idx attr-bytes]
  (let [{:keys [value]} @(.peek-state value-entity-value-idx)
        prefix (c/encode-attribute+value+entity+content-hash-key attr-bytes value)]
    [value (new-prefix-kv-iterator i prefix)]))

(defrecord DocAttributeValueEntityEntityIndex [i ^bytes attr-bytes value-entity-value-idx entity-as-of-idx peek-state]
  db/Index
  (seek-values [this k]
    (let [[value i] (attribute-value-value+prefix-iterator i value-entity-value-idx attr-bytes)]
      (when-let [k (->> (c/encode-attribute+value+entity+content-hash-key
                         attr-bytes
                         value
                         (if k
                           (c/id->bytes k)
                           c/empty-byte-array))
                        (kv/seek i))]
        (attribute-value-entity-entity+value i k attr-bytes value entity-as-of-idx peek-state))))

  (next-values [this]
    (let [[value i] (attribute-value-value+prefix-iterator i value-entity-value-idx attr-bytes)]
      (when-let [k (some->> @peek-state (kv/seek i))]
        (attribute-value-entity-entity+value i k attr-bytes value entity-as-of-idx peek-state)))))

(defn new-doc-attribute-value-entity-entity-index [snapshot attr value-entity-value-idx entity-as-of-idx]
  (->DocAttributeValueEntityEntityIndex (kv/new-iterator snapshot) (c/id->bytes attr) value-entity-value-idx entity-as-of-idx (atom nil)))

;; AEV

(defn- attribute-entity+placeholder [k attr-bytes entity-as-of-idx peek-state]
  (let [[eid] (c/decode-attribute+entity+value+content-hash-key->entity+value+content-hash k)
        eid-bytes (c/id->bytes eid)
        [_ entity-tx] (db/seek-values entity-as-of-idx eid-bytes)]
    (reset! peek-state {:last-k k :entity-tx entity-tx})
    (if entity-tx
      [eid-bytes :crux.index.binary-placeholder/entity]
      ::deleted-entity)))

(defrecord DocAttributeEntityValueEntityIndex [i ^bytes attr-bytes entity-as-of-idx peek-state]
  db/Index
  (seek-values [this k]
    (when-let [k (->> (if k
                        (c/id->bytes k)
                        c/empty-byte-array)
                      (c/encode-attribute+entity+value+content-hash-key attr-bytes)
                      (kv/seek i))]
      (let [placeholder (attribute-entity+placeholder k attr-bytes entity-as-of-idx peek-state)]
        (if (= ::deleted-entity placeholder)
          (db/next-values this)
          placeholder))))

  (next-values [this]
    (let [{:keys [^bytes last-k]} @peek-state
          prefix-size (+ c/index-id-size c/id-size c/id-size)]
      (when-let [k (some->> (bu/inc-unsigned-bytes! (Arrays/copyOf last-k prefix-size))
                            (kv/seek i))]
        (let [placeholder (attribute-entity+placeholder k attr-bytes entity-as-of-idx peek-state)]
          (if (= ::deleted-entity placeholder)
            (db/next-values this)
            placeholder))))))

(defn new-doc-attribute-entity-value-entity-index [snapshot attr entity-as-of-idx]
  (let [attr-bytes (c/id->bytes attr)
        prefix (c/encode-attribute+entity+value+content-hash-key attr-bytes)]
    (->DocAttributeEntityValueEntityIndex (new-prefix-kv-iterator (kv/new-iterator snapshot) prefix) attr-bytes entity-as-of-idx (atom nil))))

(defn- attribute-entity-value-value+entity [i ^bytes current-k attr-bytes ^EntityTx entity-tx peek-state]
  (when entity-tx
    (let [eid-bytes (c/id->bytes (.eid entity-tx))
          content-hash-bytes (c/id->bytes (.content-hash entity-tx))]
      (loop [k current-k]
        (reset! peek-state (bu/inc-unsigned-bytes! (Arrays/copyOf k (- (alength k) c/id-size))))
        (or (let [[_ value] (c/decode-attribute+entity+value+content-hash-key->entity+value+content-hash k)]
              (let [version-k (c/encode-attribute+entity+value+content-hash-key
                               attr-bytes
                               eid-bytes
                               value
                               content-hash-bytes)]
                (when-let [found-k (kv/seek i version-k)]
                  (when (bu/bytes=? version-k found-k)
                    [value entity-tx]))))
            (when-let [k (some->> @peek-state (kv/seek i))]
              (recur k)))))))

(defn- attribute-value-entity-tx+prefix-iterator [i ^DocAttributeEntityValueEntityIndex entity-value-entity-idx attr-bytes]
  (let [{:keys [^EntityTx entity-tx]} @(.peek-state entity-value-entity-idx)
        prefix (c/encode-attribute+entity+value+content-hash-key attr-bytes (c/id->bytes (.eid entity-tx)))]
    [entity-tx (new-prefix-kv-iterator i prefix)]))

(defrecord DocAttributeEntityValueValueIndex [i ^bytes attr-bytes entity-value-entity-idx peek-state]
  db/Index
  (seek-values [this k]
    (let [[^EntityTx entity-tx i] (attribute-value-entity-tx+prefix-iterator i entity-value-entity-idx attr-bytes)]
      (when-let [k (->> (c/encode-attribute+entity+value+content-hash-key
                         attr-bytes
                         (c/id->bytes (.eid entity-tx))
                         (or k c/empty-byte-array))
                        (kv/seek i))]
        (attribute-entity-value-value+entity i k attr-bytes entity-tx peek-state))))

  (next-values [this]
    (let [[entity-tx i] (attribute-value-entity-tx+prefix-iterator i entity-value-entity-idx attr-bytes)]
      (when-let [k (some->> @peek-state (kv/seek i))]
        (attribute-entity-value-value+entity i k attr-bytes entity-tx peek-state)))))

(defn new-doc-attribute-entity-value-value-index [snapshot attr entity-value-entity-idx]
  (->DocAttributeEntityValueValueIndex (kv/new-iterator snapshot) (c/id->bytes attr) entity-value-entity-idx (atom nil)))

;; Range Constraints

(defrecord PredicateVirtualIndex [idx pred seek-k-fn]
  db/Index
  (seek-values [this k]
    (when-let [value+results (db/seek-values idx (seek-k-fn k))]
      (when (pred (first value+results))
        value+results)))

  (next-values [this]
    (when-let [value+results (db/next-values idx)]
      (when (pred (first value+results))
        value+results))))

(defn- value-comparsion-predicate
  ([compare-pred compare-v]
   (value-comparsion-predicate compare-pred compare-v Integer/MAX_VALUE))
  ([compare-pred ^bytes compare-v max-length]
   (if compare-v
     (fn [value]
       (and value (compare-pred (bu/compare-bytes value compare-v max-length))))
     (constantly true))))

(defn new-less-than-equal-virtual-index [idx max-v]
  (let [pred (value-comparsion-predicate (comp not pos?) (c/value->bytes max-v))]
    (->PredicateVirtualIndex idx pred identity)))

(defn new-less-than-virtual-index [idx max-v]
  (let [pred (value-comparsion-predicate neg? (c/value->bytes max-v))]
    (->PredicateVirtualIndex idx pred identity)))

(defn new-greater-than-equal-virtual-index [idx min-v]
  (let [min-v (c/value->bytes min-v)
        pred (value-comparsion-predicate (comp not neg?) min-v)]
    (->PredicateVirtualIndex idx pred (fn [k]
                                        (if (pred k)
                                          k
                                          min-v)))))

(defrecord GreaterThanVirtualIndex [idx]
  db/Index
  (seek-values [this k]
    (or (db/seek-values idx k)
        (db/next-values idx)))

  (next-values [this]
    (db/next-values idx)))

(defn new-greater-than-virtual-index [idx min-v]
  (let [min-v (c/value->bytes min-v)
        pred (value-comparsion-predicate pos? min-v)
        idx (->PredicateVirtualIndex idx pred (fn [k]
                                                (if (pred k)
                                                  k
                                                  min-v)))]
    (->GreaterThanVirtualIndex idx)))

(defn new-prefix-equal-virtual-index [idx ^bytes prefix-v]
  (let [seek-k-pred (value-comparsion-predicate (comp not neg?) prefix-v (alength prefix-v))
        pred (value-comparsion-predicate zero? prefix-v (alength prefix-v))]
    (->PredicateVirtualIndex idx pred (fn [k]
                                        (if (seek-k-pred k)
                                          k
                                          prefix-v)))))

(defn wrap-with-range-constraints [idx range-constraints]
  (if range-constraints
    (range-constraints idx)
    idx))

;; Meta

(defn store-meta [kv k v]
  (kv/store kv [[(c/encode-meta-key (c/id->bytes k))
                 (nippy/fast-freeze v)]]))

(defn read-meta [kv k]
  (let [seek-k (c/encode-meta-key (c/id->bytes k))]
    (with-open [snapshot (kv/new-snapshot kv)
                i (kv/new-iterator snapshot)]
      (when-let [k (kv/seek i seek-k)]
        (when (bu/bytes=? seek-k k)
          (nippy/fast-thaw (kv/value i)))))))

;; Object Store

(defn normalize-value [v]
  (cond-> v
    (not (or (vector? v)
             (set? v))) (vector)))

(defn- normalize-doc [doc]
  (->> (for [[k v] doc]
         [k (normalize-value v)])
       (into {})))

(defn- doc-predicate-stats [normalized-doc]
  (->> (for [[k v] normalized-doc]
         [k (count v)])
       (into {})))

(defn- update-predicate-stats [kv f normalized-doc]
  (->> (doc-predicate-stats normalized-doc)
       (merge-with f (read-meta kv :crux.kv/stats))
       (store-meta kv :crux.kv/stats)))

(defn index-doc [kv content-hash doc]
  (let [id (c/id->bytes (:crux.db/id doc))
        content-hash (c/id->bytes content-hash)
        normalized-doc (normalize-doc doc)]
    (kv/store kv (->> (for [[k v] normalized-doc
                            v v
                            :let [k (c/id->bytes k)
                                  v (c/value->bytes v)]
                            :when (seq v)]
                        [[(c/encode-attribute+value+entity+content-hash-key k v id content-hash)
                          c/empty-byte-array]
                         [(c/encode-attribute+entity+value+content-hash-key k id v content-hash)
                          c/empty-byte-array]])
                      (reduce into [])))
    (update-predicate-stats kv + normalized-doc)))

(defn delete-doc-from-index [kv content-hash doc]
  (let [id (c/id->bytes (:crux.db/id doc))
        content-hash (c/id->bytes content-hash)
        normalized-doc (normalize-doc doc)]
    (kv/delete kv (->> (for [[k v] normalized-doc
                             v v
                             :let [k (c/id->bytes k)
                                   v (c/value->bytes v)]
                             :when (seq v)]
                         [(c/encode-attribute+value+entity+content-hash-key k v id content-hash)
                          (c/encode-attribute+entity+value+content-hash-key k id v content-hash)])
                       (reduce into [])))
    (update-predicate-stats kv - normalized-doc)))

(defrecord KvObjectStore [kv]
  Closeable
  (close [_])

  db/ObjectStore
  (get-single-object [this snapshot k]
    (with-open [i (kv/new-iterator snapshot)]
      (let [doc-key (c/id->bytes k)
            seek-k (c/encode-doc-key doc-key)
            k (kv/seek i seek-k)]
        (when (and k (bu/bytes=? seek-k k))
          (nippy/fast-thaw (kv/value i))))))

  (get-objects [this snapshot ks]
    (with-open [i (kv/new-iterator snapshot)]
      (->> (for [seek-k (->> (map (comp c/encode-doc-key c/id->bytes) ks)
                             (sort bu/bytes-comparator))
                 :let [k (kv/seek i seek-k)]
                 :when (and k (bu/bytes=? seek-k k))]
             [(c/decode-doc-key k)
              (nippy/fast-thaw (kv/value i))])
           (into {}))))

  (put-objects [this kvs]
    (kv/store kv (for [[k v] kvs]
                   [(c/encode-doc-key (c/id->bytes k))
                    (nippy/fast-freeze v)])))

  (delete-objects [this ks]
    (kv/delete kv (map (comp c/encode-doc-key c/id->bytes) ks)))

  Closeable
  (close [_]))

;; Utils

(defn all-keys-in-prefix
  ([i prefix]
   (all-keys-in-prefix i prefix false))
  ([i ^bytes prefix entries?]
   ((fn step [f-cons f-next]
      (lazy-seq
       (let [k (f-cons)]
         (when (and k (bu/bytes=? prefix k (alength prefix)))
           (cons (if entries?
                   [k (kv/value i)]
                   k) (step f-next f-next))))))
    #(kv/seek i prefix) #(kv/next i))))

(defn idx->seq [idx]
  (when-let [result (db/seek-values idx nil)]
    (->> (repeatedly #(db/next-values idx))
         (take-while identity)
         (cons result))))

;; Entities

(defn- ^EntityTx enrich-entity-tx [entity-tx content-hash]
  (assoc entity-tx :content-hash (some-> content-hash not-empty c/new-id)))

(defrecord EntityAsOfIndex [i business-time transact-time]
  db/Index
  (db/seek-values [this k]
    (let [prefix-size (+ c/index-id-size c/id-size)
          seek-k (c/encode-entity+bt+tt+tx-id-key
                  k
                  business-time
                  transact-time)]
      (loop [k (kv/seek i seek-k)]
        (when (and k (bu/bytes=? seek-k k prefix-size))
          (let [v (kv/value i)
                entity-tx (-> (c/decode-entity+bt+tt+tx-id-key k)
                              (enrich-entity-tx v))]
            (if (<= (compare (.tt entity-tx) transact-time) 0)
              (when-not (bu/bytes=? c/nil-id-bytes v)
                [(c/id->bytes (.eid entity-tx)) entity-tx])
              (recur (kv/next i))))))))

  (db/next-values [this]
    (throw (UnsupportedOperationException.))))

(defn new-entity-as-of-index [snapshot business-time transact-time]
  (->EntityAsOfIndex (kv/new-iterator snapshot) business-time transact-time))

(defn entities-at [snapshot eids business-time transact-time]
  (let [entity-as-of-idx (new-entity-as-of-index snapshot business-time transact-time)]
    (some->> (for [eid eids
                   :let [[_ entity-tx] (db/seek-values entity-as-of-idx (c/id->bytes eid))]
                   :when entity-tx]
               entity-tx)
             (not-empty)
             (vec))))

(defn all-entities [snapshot business-time transact-time]
  (with-open [i (kv/new-iterator snapshot)]
    (let [eids (->> (all-keys-in-prefix i (c/encode-entity+bt+tt+tx-id-key))
                    (map (comp :eid c/decode-entity+bt+tt+tx-id-key))
                    (distinct))]
      (entities-at snapshot eids business-time transact-time))))

(defn entity-history [snapshot eid]
  (with-open [i (kv/new-iterator snapshot)]
    (let [seek-k (c/encode-entity+bt+tt+tx-id-key (c/id->bytes eid))]
      (vec (for [[k v] (all-keys-in-prefix i seek-k true)]
             (-> (c/decode-entity+bt+tt+tx-id-key k)
                 (enrich-entity-tx v)))))))

;; Join

(extend-protocol db/LayeredIndex
  Object
  (open-level [_])
  (close-level [_])
  (max-depth [_] 1))

(def ^:private sorted-virtual-index-key-comparator
  (reify Comparator
    (compare [_ [a] [b]]
      (bu/compare-bytes (or a c/nil-id-bytes)
                        (or b c/nil-id-bytes)))))

(defrecord SortedVirtualIndex [values seq-state]
  db/Index
  (seek-values [this k]
    (let [idx (Collections/binarySearch values
                                        [k]
                                        sorted-virtual-index-key-comparator)
          [x & xs] (subvec values (if (neg? idx)
                                    (dec (- idx))
                                    idx))
          {:keys [fst]} (reset! seq-state {:fst x :rest xs})]
      (if fst
        fst
        (reset! seq-state nil))))

  (next-values [this]
    (let [{:keys [fst]} (swap! seq-state (fn [{[x & xs] :rest
                                               :as seq-state}]
                                           (assoc seq-state :fst x :rest xs)))]
      (when fst
        fst))))

(defn new-sorted-virtual-index [idx-or-seq]
  (let [idx-as-seq (if (satisfies? db/Index idx-or-seq)
                     (idx->seq idx-or-seq)
                     idx-or-seq)]
    (->SortedVirtualIndex
     (->> idx-as-seq
          (sort-by first bu/bytes-comparator)
          (distinct)
          (vec))
     (atom nil))))

(defrecord OrVirtualIndex [indexes peek-state]
  db/Index
  (seek-values [this k]
    (reset! peek-state (vec (for [idx indexes]
                              (db/seek-values idx k))))
    (db/next-values this))

  (next-values [this]
    (let [[n value] (->> (map-indexed vector @peek-state)
                         (remove (comp nil? second))
                         (sort-by (comp first second) bu/bytes-comparator)
                         (first))]
      (when n
        (swap! peek-state assoc n (db/next-values (get indexes n))))
      value)))

(defn new-or-virtual-index [indexes]
  (->OrVirtualIndex indexes (atom nil)))

(defn or-known-triple-fast-path [snapshot e a v business-time transact-time]
  (when-let [[^EntityTx entity-tx] (entities-at snapshot [e] business-time transact-time)]
    (let [version-k (c/encode-attribute+entity+value+content-hash-key
                     (c/id->bytes a)
                     (c/id->bytes (.eid entity-tx))
                     (c/value->bytes v)
                     (c/id->bytes (.content-hash entity-tx)))]
      (with-open [i (new-prefix-kv-iterator (kv/new-iterator snapshot) version-k)]
        (when (kv/seek i version-k)
          entity-tx)))))

(defn- new-unary-join-iterator-state [idx [value results]]
  (let [result-name (:name idx)]
    {:idx idx
     :key (or value c/nil-id-bytes)
     :result-name result-name
     :results (when (and result-name results)
                {result-name results})}))

(defrecord UnaryJoinVirtualIndex [indexes iterators-thunk-state]
  db/Index
  (seek-values [this k]
    (->> #(let [iterators (->> (for [idx indexes]
                                 (new-unary-join-iterator-state idx (db/seek-values idx k)))
                               (sort-by :key bu/bytes-comparator)
                               (vec))]
            {:iterators iterators :index 0})
         (reset! iterators-thunk-state))
    (db/next-values this))

  (next-values [this]
    (when-let [iterators-thunk @iterators-thunk-state]
      (when-let [{:keys [iterators ^long index]} (iterators-thunk)]
        (let [{:keys [key result-name idx]} (get iterators index)
              max-index (mod (dec index) (count iterators))
              max-k (:key (get iterators max-index))
              match? (bu/bytes=? key max-k)]
          (->> #(let [next-value+results (if match?
                                           (do (log/debug :next result-name)
                                               (db/next-values idx))
                                           (do (log/debug :seek result-name (bu/bytes->hex max-k))
                                               (db/seek-values idx max-k)))]
                  (when next-value+results
                    {:iterators (assoc iterators index (new-unary-join-iterator-state idx next-value+results))
                     :index (mod (inc index) (count iterators))}))
               (reset! iterators-thunk-state))
          (if match?
            (let [names (map :result-name iterators)]
              (log/debug :match names (bu/bytes->hex max-k))
              (when-let [result (->> (map :results iterators)
                                     (apply merge))]
                [max-k result]))
            (recur))))))

  db/LayeredIndex
  (open-level [this]
    (doseq [idx indexes]
      (db/open-level idx)))

  (close-level [this]
    (doseq [idx indexes]
      (db/close-level idx)))

  (max-depth [this]
    1))

(defn new-unary-join-virtual-index [indexes]
  (->UnaryJoinVirtualIndex indexes (atom nil)))

(defn constrain-join-result-by-empty-names [join-keys join-results]
  (when (not-any? nil? (vals join-results))
    join-results))

(defrecord NAryJoinLayeredVirtualIndex [unary-join-indexes depth-state]
  db/Index
  (seek-values [this k]
    (db/seek-values (get unary-join-indexes @depth-state) k))

  (next-values [this]
    (db/next-values (get unary-join-indexes @depth-state)))

  db/LayeredIndex
  (open-level [this]
    (db/open-level (get unary-join-indexes @depth-state))
    (swap! depth-state inc)
    nil)

  (close-level [this]
    (db/close-level (get unary-join-indexes (dec (long @depth-state))))
    (swap! depth-state dec)
    nil)

  (max-depth [this]
    (count unary-join-indexes)))

(defn new-n-ary-join-layered-virtual-index [indexes]
  (->NAryJoinLayeredVirtualIndex indexes (atom 0)))

(defrecord BinaryJoinLayeredVirtualIndex [index-and-depth-state]
  db/Index
  (seek-values [this k]
    (let [{:keys [indexes depth]} @index-and-depth-state]
      (db/seek-values (get indexes depth) k)))

  (next-values [this]
    (let [{:keys [indexes depth]} @index-and-depth-state]
      (db/next-values (get indexes depth))))

  db/LayeredIndex
  (open-level [this]
    (let [{:keys [indexes depth]} @index-and-depth-state]
      (db/open-level (get indexes depth)))
    (swap! index-and-depth-state update :depth inc)
    nil)

  (close-level [this]
    (let [{:keys [indexes depth]} @index-and-depth-state]
      (db/close-level (get indexes (dec (long depth)))))
    (swap! index-and-depth-state update :depth dec)
    nil)

  (max-depth [this]
    (count (:indexes @index-and-depth-state))))

(defn new-binary-join-virtual-index
  ([]
   (new-binary-join-virtual-index nil nil))
  ([lhs-index rhs-index]
   (->BinaryJoinLayeredVirtualIndex (atom {:depth 0
                                           :indexes [lhs-index rhs-index]}))))

(defn update-binary-join-order! [binary-join-index lhs-index rhs-index]
  (swap! (:index-and-depth-state binary-join-index) assoc :indexes [lhs-index rhs-index])
  binary-join-index)

(defn- build-constrained-result [constrain-result-fn result-stack [max-k new-values]]
  (let [[max-ks parent-result] (last result-stack)
        join-keys (conj (or max-ks []) max-k)]
    (when-let [join-results (->> (merge parent-result new-values)
                                 (constrain-result-fn join-keys)
                                 (not-empty))]
      (conj result-stack [join-keys join-results]))))

(defrecord NAryConstrainingLayeredVirtualIndex [n-ary-index constrain-result-fn walk-state]
  db/Index
  (seek-values [this k]
    (when-let [values (db/seek-values n-ary-index k)]
      (if-let [result (build-constrained-result constrain-result-fn (:result-stack @walk-state) values)]
        (do (swap! walk-state assoc :last result)
            [(first values) (second (last result))])
        (db/next-values this))))

  (next-values [this]
    (when-let [values (db/next-values n-ary-index)]
      (if-let [result (build-constrained-result constrain-result-fn (:result-stack @walk-state) values)]
        (do (swap! walk-state assoc :last result)
            [(first values) (second (last result))])
        (recur))))

  db/LayeredIndex
  (open-level [this]
    (db/open-level n-ary-index)
    (swap! walk-state #(assoc % :result-stack (:last %)))
    nil)

  (close-level [this]
    (db/close-level n-ary-index)
    (swap! walk-state update :result-stack pop)
    nil)

  (max-depth [this]
    (db/max-depth n-ary-index)))

(defn new-n-ary-constraining-layered-virtual-index [idx constrain-result-fn]
  (->NAryConstrainingLayeredVirtualIndex idx constrain-result-fn (atom {:result-stack [] :last nil})))

(defn layered-idx->seq [idx]
  (let [max-depth (long (db/max-depth idx))
        build-result (fn [max-ks [max-k new-values]]
                       (when new-values
                         (conj max-ks max-k)))
        build-leaf-results (fn [max-ks idx]
                             (for [result (idx->seq idx)
                                   :let [leaf-key (build-result max-ks result)]
                                   :when leaf-key]
                               [leaf-key (last result)]))
        step (fn step [max-ks ^long depth needs-seek?]
               (when (Thread/interrupted)
                 (throw (InterruptedException.)))
               (let [close-level (fn []
                                   (when (pos? depth)
                                     (lazy-seq
                                      (db/close-level idx)
                                      (step (pop max-ks) (dec depth) false))))
                     open-level (fn [result]
                                  (db/open-level idx)
                                  (if-let [max-ks (build-result max-ks result)]
                                    (step max-ks (inc depth) true)
                                    (do (db/close-level idx)
                                        (step max-ks depth false))))]
                 (if (= depth (dec max-depth))
                   (concat (build-leaf-results max-ks idx)
                           (close-level))
                   (if-let [result (if needs-seek?
                                     (db/seek-values idx nil)
                                     (db/next-values idx))]
                     (open-level result)
                     (close-level)))))]
    (when (pos? max-depth)
      (step [] 0 true))))

(defn- relation-virtual-index-depth ^long [iterators-state]
  (dec (count (:indexes @iterators-state))))

(defrecord RelationVirtualIndex [relation-name max-depth layered-range-constraints iterators-state]
  db/Index
  (seek-values [this k]
    (let [{:keys [indexes]} @iterators-state]
      (when-let [idx (last indexes)]
        (let [[k {:keys [value child-idx]}] (db/seek-values idx k)]
          (swap! iterators-state merge {:child-idx child-idx
                                        :needs-seek? false})
          (when k
            [k value])))))

  (next-values [this]
    (let [{:keys [needs-seek? indexes]} @iterators-state]
      (if needs-seek?
        (db/seek-values this nil)
        (when-let [idx (last indexes)]
          (let [[k {:keys [value child-idx]}] (db/next-values idx)]
            (swap! iterators-state assoc :child-idx child-idx)
            (when k
              [k value]))))))

  db/LayeredIndex
  (open-level [this]
    (when (= max-depth (relation-virtual-index-depth iterators-state))
      (throw (IllegalStateException. (str "Cannot open level at max depth: " max-depth))))
    (swap! iterators-state
           (fn [{:keys [indexes child-idx]}]
             {:indexes (conj indexes child-idx)
              :child-idx nil
              :needs-seek? true}))
    nil)

  (close-level [this]
    (when (zero? (relation-virtual-index-depth iterators-state))
      (throw (IllegalStateException. "Cannot close level at root.")))
    (swap! iterators-state (fn [{:keys [indexes]}]
                             {:indexes (pop indexes)
                              :child-idx nil
                              :needs-seek? false}))
    nil)

  (max-depth [this]
    max-depth))

(defn- build-nested-index [tuples [range-constraints & next-range-constraints]]
  (-> (new-sorted-virtual-index
       (for [prefix (partition-by first tuples)
             :let [value (ffirst prefix)]]
         [(c/value->bytes value)
          {:value value
           :child-idx (when (seq (next (first prefix)))
                        (build-nested-index (map next prefix) next-range-constraints))}]))
      (wrap-with-range-constraints range-constraints)))

(defn update-relation-virtual-index!
  ([relation tuples]
   (update-relation-virtual-index! relation tuples (:layered-range-constraints relation)))
  ([relation tuples layered-range-constraints]
   (reset! (:iterators-state relation)
           {:indexes [(binding [nippy/*freeze-fallback* :write-unfreezable]
                        (build-nested-index tuples layered-range-constraints))]
            :child-idx nil
            :needs-seek? true})
   relation))

(defn new-relation-virtual-index
  ([relation-name tuples max-depth]
   (new-relation-virtual-index relation-name tuples max-depth nil))
  ([relation-name tuples max-depth layered-range-constraints]
   (let [iterators-state (atom nil)]
     (update-relation-virtual-index! (->RelationVirtualIndex relation-name max-depth layered-range-constraints iterators-state) tuples))))
