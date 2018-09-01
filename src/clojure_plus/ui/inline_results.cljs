(ns clojure-plus.ui.inline-results
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [reagent.core :as r]
            [clojure.walk :as walk]
            [repl-tooling.eval :as eval]
            [clojure-plus.state :refer [state]]))

(defprotocol Taggable
  (obj [this])
  (tag [this]))

(deftype WithTag [obj tag]
  IPrintWithWriter
  (-pr-writer [_ writer opts]
    (-write writer "#")
    (-write writer tag)
    (-write writer " ")
    (-write writer obj))

  Taggable
  (obj [_] obj)
  (tag [_] (str "#" tag " ")))

(defonce ink (atom nil))

(defn new-result [editor row]
  (when-let [InkResult (some-> @ink .-Result)]
    (InkResult. editor #js [row row] #js {:type "block"})))

(defn- ink-tree [header elements block?]
  (when @ink
    (cond-> (-> @ink .-tree (.treeView header (clj->js elements)))
            block? (doto (-> .-classList (.add "line"))))))

(defn set-content [result header elements]
  (let [contents (ink-tree header elements true)]
    (.setContent result contents #js {:error false})))

(defn- to-str [edn]
  (let [tag (when (instance? WithTag edn) (tag edn))
        edn (cond-> edn (instance? WithTag edn) obj)]

    (-> edn
        pr-str
        (str/replace #"\{:repl-tooling/\.\.\. .+?\}" "...")
        (->> (str tag)))))

(declare to-tree)
(defn- as-map [[key val]]
  (let [k-str (str "[" (to-str key))
        v-str (str (to-str val) "]")]
    [:row [[k-str [(to-tree key)]]
           [v-str [(to-tree val)]]]]))

(defn- to-tree [edn]
  (let [txt (to-str edn)
        edn (cond-> edn (instance? WithTag edn) obj)]
    (cond
      (map? edn) [txt (mapv as-map edn)]
      (coll? edn) [txt (mapv to-tree edn)]
      :else [txt])))

(defn- default-tag [tag data]
  (WithTag. data tag))

(defn parse [edn-string]
  (try
    (let [edn (reader/read-string {:default default-tag} edn-string)]
      (to-tree edn))
    (catch :default _
      (to-tree (symbol edn-string)))))

(defn- leaf [text]
  (doto (.createElement js/document "div")
    (aset "innerText" text)))

(declare to-html)
(defn- html-row [children]
  (let [div (.createElement js/document "div")]
    (doseq [child children]
      (.appendChild div (to-html child)))
    div))

(defn- to-html [[header children]]
  (cond
    (empty? children) (leaf header)
    (= :row header) (html-row children)
    :else (ink-tree header (mapv to-html children) false)))

(defn set-content! [result result-tree]
  (let [contents (to-html result-tree)]
    (.setContent result contents #js {:error false})))

(defn- read-result [res]
  (try
    (reader/read-string {:default default-tag} res)
    (catch :default _
      nil)))

(defn- get-more [path command]
  (let [with-res (fn [{:keys [result]}]
                   (let [res (read-result result)]
                     (cond
                       (map? @path) (swap! path merge res)
                       (vector? @path) (swap! path #(-> % butlast (concat res) vec))
                       :else (swap! path #(-> % butlast (concat res))))))]
    (some-> @state :repls :clj-eval (eval/evaluate command {} with-res))))

(defn- parse-stack [path stack]
  (if (and (map? stack) (:repl-tooling/... stack))
    {:contents "..." :fn #(get-more path (:repl-tooling/... stack))}
    (let [[class method file num] stack]
      (when-not (re-find #"unrepl\.repl\$" (str class))
        {:contents (str "in " (demunge class) " (" method ") at " file ":" num)}))))

(defn- stack-line [idx piece]
  [:div {:key idx}
   (if-let [fun (:fn piece)]
     [:a {:on-click fun} (:contents piece)]
     (:contents piece))])

(defn- error-view [error]
  (let [ex (:ex @error)
        [cause & vias] (:via ex)
        path (r/cursor error [:ex :trace])
        stacks (->> @path
                    (map (partial parse-stack path))
                    (filter identity))]
    [:div
     [:strong {:class "error-description"} (str (:type cause)
                                                ": "
                                                (:message cause))]
     [:div {:class "stacktrace"}
      (map stack-line (range) stacks)]]))

(defn render-error! [result error]
  (let [div (. js/document (createElement "div"))
        res (r/atom (read-result error))]
    (r/render [error-view res] div)
    (.. div -classList (add "error" "clojure-plus"))
    (.setContent result div #js {:error true})))

(defn render-result! [result eval-result]
  (let [div (. js/document (createElement "div"))
        res (r/atom (read-result eval-result))]
    (r/render [error-view res] div)
    (.. div -classList (add "result" "clojure-plus"))
    (.setContent result div #js {:error false})))
