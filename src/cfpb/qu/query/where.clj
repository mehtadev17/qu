(ns cfpb.qu.query.where
  "This namespace parses WHERE clauses into an AST and turns that AST
into a Monger query."
  (:require
   [clojure.string :as str]
   [taoensso.timbre :as log]   
   [protoflex.parse :as p]
   [cfpb.qu.query.parser :refer [where-expr]]
   [slingshot.slingshot :refer [throw+]])
  (:import (java.util.regex Pattern)))

(defn parse
  "Parse a valid WHERE expression and return an abstract syntax tree
for use in constructing Mongo queries."
  [clause]
  (p/parse where-expr clause))

(def mongo-operators
  {:AND "$and"
   :OR "$or"
   :IN "$in"
   :< "$lt"
   :<= "$lte"
   :> "$gt"
   :>= "$gte"
   :!= "$ne"})

(defn mongo-not [comparison]
  (let [ident (first (keys comparison))
        operation (first (vals comparison))]

    (cond
     (map? operation)
     (let [operator (first (keys operation))
           value (first (vals operation))]
       (if (= operator "$ne")
         {ident value}
         {ident {"$not" operation}}))

     (= (type operation) Pattern)
     {ident {"$not" operation}}

     :default
     {ident {"$ne" operation}})))

(declare mongo-eval-not)

(defn sql-pattern-to-regex-str
  "Converts a SQL search string, such as 'foo%', into a regular expression string"
  [value]
  (str "^"
       (str/replace value
                    #"[%_]|[^%_]+"
                    (fn [match]
                      (case match
                        "%" ".*"
                        "_" "."
                        (Pattern/quote match))))
       "$"))

(defn like-to-regex
  "Converts a SQL LIKE value into a regular expression."
  [like]
  (re-pattern (sql-pattern-to-regex-str like)))

(defn ilike-to-regex
  "Converts a SQL ILIKE value into a regular expression."
  [ilike]
  (re-pattern
   (str "(?i)"
        (sql-pattern-to-regex-str ilike))))

(defn- has-key?
  "Returns true if map? is a map and has the key key."
  [map key]
  (and (map? map)
       (contains? map key)))

(defn mongo-eval
  "Take an abstract syntax tree generated by `parse` and turn it into
a valid Monger query."
  [ast]
  (cond
   (has-key? ast :not)
   (mongo-eval-not (:not ast))

   (has-key? ast :op)
   (let [{:keys [op left right]} ast]
     {(op mongo-operators) [(mongo-eval left) (mongo-eval right)]})

   (has-key? ast :comparison)
   (let [[ident op value] (:comparison ast)
         value (mongo-eval value)]
     (case op
       := {ident value}
       :LIKE {ident (like-to-regex value)}
       :ILIKE {ident (ilike-to-regex value)}
       {ident {(op mongo-operators) value}}))

   (has-key? ast :bool)
   (:bool ast)

   :default
   ast))

(defn- mongo-eval-not [ast]
  (cond
   (has-key? ast :not)
   (mongo-eval (:not ast))

   (has-key? ast :op)
   (let [{:keys [op left right]} ast]
     (case op
       :OR {"$nor" [(mongo-eval left) (mongo-eval right)]}
       :AND {"$or" [(mongo-eval-not left) (mongo-eval-not right)]}))

   (has-key? ast :comparison)
   (mongo-not (mongo-eval ast))

   :default
   (throw+ {:type ::mongo-eval
            :message (str "Cannot evaluate " ast " in a negative context for Mongo.")})))
