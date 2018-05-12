(ns crux.query
  (:require [clojure.spec.alpha :as s]
            [crux.db :as db]))

(defn- expression-spec [sym spec]
  (s/and seq?
         #(= sym (first %))
         (s/conformer second)
         spec))

(s/def ::pred-fn (s/and symbol?
                        (s/conformer #(some-> % resolve var-get))
                        fn?))
(s/def ::find (s/coll-of symbol? :kind vector?))
(s/def ::fact (s/coll-of #(or (keyword? %)
                              (symbol? %)
                              (string? %))
                         :kind vector?))
(s/def ::term (s/or :fact ::fact
                    :not (expression-spec 'not ::term)
                    :or (expression-spec 'or ::where)
                    :and (expression-spec 'and ::where)
                    :not-join (s/cat :pred #{'not-join}
                                     :bindings (s/coll-of symbol? :kind vector?)
                                     :terms ::where)
                    :pred (s/cat ::pred-fn ::pred-fn
                                 ::args (s/* any?))))
(s/def ::where (s/coll-of ::term :kind vector?))
(s/def ::query (s/keys :req-un [::find ::where]))

(defn- value-matches? [db [term-e term-a term-v] result]
  (when-let [v (db/attr-val db (get result term-e) term-a)]
    (or (not term-v)
        (and (symbol? term-v) (= (result term-v) v))
        (= term-v v))))

(defprotocol Binding
  (bind-key [this])
  (bind [this db]))

(defn- entities-for-term [db a v]
  (if (and v (not (symbol? v)))
    (db/entities-for-attribute-value db a v)
    (db/entities db)))

(defrecord EntityBinding [e a v]
  Binding
  (bind-key [this] e)
  (bind [this db]
    (fn [rf]
      (fn
        ([]
         (rf))
        ([result]
         (rf result))
        ([result input]
         (if (satisfies? db/Datasource input)
           (transduce (map (partial hash-map e)) rf result (entities-for-term db a v))
           (if (get input e)
             (rf result input)
             ;; New entity, join the results (todo, look at hash-join algos)
             (transduce (map #(assoc input e %)) rf result (entities-for-term db a v)))))))))

(defrecord VarBinding [e a s]
  Binding
  (bind-key [this] s)
  (bind [this db]
    (map (fn [input] (if (get input s)
                       input
                       (assoc input s (db/attr-val db (get input e) a)))))))

(defn- fact->entity-binding [[e a v]]
  (EntityBinding. e a v))

(defn- fact->var-binding [[e a v]]
  (when (and v (symbol? v))
    (VarBinding. e a v)))

(defn- query-plan->xform
  "Create a tranduce from the query-plan."
  [db plan]
  (apply comp (for [[term-bindings pred-f] plan
                    :let [binding-transducers (map (fn [b] (bind b db)) term-bindings)]]
                (comp (apply comp binding-transducers)
                      (filter (partial pred-f db))))))

(defn- query-terms->plan
  "Converts a sequence of query terms into a sequence of executable
  query stages."
  [terms]
  (for [[op t] terms]
    (condp = op
      :fact
      [(remove nil? [(fact->entity-binding t)
                     (fact->var-binding t)])
       (fn [db result] (value-matches? db t result))]

      :and
      (let [sub-plan (query-terms->plan t)]
        [(mapcat first sub-plan)
         (fn [db result]
           (every? (fn [[_ pred-fn]]
                     (pred-fn db result))
                   sub-plan))])

      :not
      (let [query-plan (query-terms->plan [t])
            [bindings pred-fn?] (first query-plan)]
        [bindings
         (fn [db result] (not (pred-fn? db result)))])

      :or
      (let [sub-plan (query-terms->plan t)]
        (assert (->> sub-plan
                     (map #(into #{} (map bind-key (first %))))
                     (apply =)))
        [(mapcat first sub-plan)
         (fn [db result]
           (some (fn [[_ pred-fn :as s]]
                   (pred-fn db result))
                 sub-plan))])

      :not-join
      (let [e (-> t :bindings first)]
        [(map #(EntityBinding. % nil nil) (:bindings t))
         (let [
               or-results (atom nil)]
           (fn [db result]
             (let [or-results (or @or-results
                                  (let [query-xform (query-plan->xform db (query-terms->plan (:terms t)))]
                                    (reset! or-results (into #{} query-xform [db]))))]
               (when-not (some #(= (get result e) (get % e)) or-results)
                 result))))])

      :pred
      (let [{:keys [::args ::pred-fn]} t]
        [nil (fn [_ result]
               (let [args (map #(or (and (symbol? %) (result %)) %) args)]
                 (apply pred-fn args)))]))))

(defn- term-symbols [terms]
  (->> terms
       (mapcat first)
       (map bind-key)
       (into #{})))

(defn- validate-query [find plan]
  (let [variables (term-symbols plan)]
    (doseq [binding find]
      (when-not (variables binding)
        (throw (IllegalArgumentException. (str "Find clause references unbound variable: " binding))))))
  plan)

(defn- find-projection [find result]
  (map (partial get result) find))

(defn q
  [db {:keys [find where] :as q}]
  (let [{:keys [find where] :as q} (s/conform ::query q)]
    (when (= :clojure.spec.alpha/invalid q)
      (throw (ex-info "Invalid input" (s/explain-data ::query q))))
    (let [xform (->> where
                     (query-terms->plan)
                     (validate-query find)
                     (query-plan->xform db))]
      (into #{} (comp xform (map (partial find-projection find))) [db]))))
