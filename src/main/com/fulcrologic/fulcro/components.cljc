(ns com.fulcrologic.fulcro.components
  #?(:cljs (:require-macros com.fulcrologic.fulcro.components))
  (:require
    #?@(:clj
        [[cljs.analyzer :as ana]]
        :cljs
        [[goog.object :as gobj]
         cljsjs.react])
    [edn-query-language.core :as eql]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log]
    [clojure.walk :refer [prewalk]]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [clojure.set :as set])
  #?(:clj
     (:import
       [clojure.lang Associative IDeref APersistentMap])))

(defonce ^:private component-registry (atom {}))
(def ^:dynamic *query-state* nil)
(def ^:dynamic *app* nil)
(def ^:dynamic *parent* nil)
(def ^:dynamic *depth* nil)
(def ^:dynamic *shared* nil)
(def ^:dynamic *blindly-render* false)

(defn isoget-in
  "Like get-in, but for js objects, and in CLJC. In clj, it is just get-in. In cljs it is
  gobj/getValueByKeys."
  ([obj kvs]
   (isoget-in obj kvs nil))
  ([obj kvs default]
   #?(:clj (get-in obj kvs default)
      :cljs
           (let [ks (mapv (fn [k] (some-> k name)) kvs)]
             (or (apply gobj/getValueByKeys obj ks) default)))))

(defn isoget
  "Like get, but for js objects, and in CLJC. In clj, it is just `get`. In cljs it is
  `gobj/get`."
  ([obj k] (isoget obj k nil))
  ([obj k default]
   #?(:clj  (get obj k default)
      :cljs (or (gobj/get obj (some-> k (name))) default))))


(defn register-component!
  "Add a component to Fulcro's component registry.  This is used by defsc to ensure that all Fulcro classes
  that have been compiled (transitively required) will be accessible for lookup by fully-qualified symbol/keyword.
  Not meant for public use, unless you're creating your own component macro that doesn't directly leverage defsc."
  [k component-class]
  (swap! component-registry assoc k component-class)
  component-class)

(defn newer-props
  "Returns whichever of the given Fulcro props were most recently generated according to `denormalization-time`."
  [props-a props-b]
  (cond
    (nil? props-a) props-b
    (nil? props-b) props-a
    (> (or (fdn/denormalization-time props-a) 2) (or (fdn/denormalization-time props-b) 1)) props-a
    :else props-b))

(defn component-instance?
  "Returns true if the argument is a component. A component is defined as a *mounted component*.
   This function returns false for component classes, and also returns false for the output of a Fulcro component factory."
  #?(:cljs {:tag boolean})
  [x]
  (if-not (nil? x)
    #?(:clj  (true? (:fulcro$isComponent x))
       :cljs (true? (gobj/get x "fulcro$isComponent")))
    false))

(def component?
  "Returns true if the argument is a component instance.

   DEPRECATED for terminology clarity. Use `component-instance?` instead."
  component-instance?)

(defn component-class?
  "Returns true if the argument is a component class."
  #?(:cljs {:tag boolean})
  [x]
  #?(:clj  (boolean (and (map? x) (::component-class? x)))
     :cljs (boolean (gobj/containsKey x "fulcro$class"))))

(s/def ::component-class component-class?)

(defn component-name
  "Returns a string version of the given react component's name. Works on component instances and classes."
  [class]
  (isoget class :displayName))

(defn class->registry-key
  "Returns the registry key for the given component class."
  [class]
  (isoget class :fulcro$registryKey))

(defn registry-key->class
  "Look up the given component in Fulcro's global component registry. Will only be able to find components that have
  been (transitively) required by your application.

  `classname` can be a fully-qualified keyword or symbol."
  [classname]
  (cond
    (keyword? classname) (get @component-registry classname)
    (symbol? classname) (let [k (keyword (namespace classname) (name classname))]
                          (get @component-registry k))
    :otherwise nil))

(declare props)

(defn computed
  "Add computed properties to props. Note will replace any pre-existing
   computed properties."
  [props computed-map]
  (when-not (nil? props)
    (if (vector? props)
      (cond-> props
        (not (empty? computed-map)) (vary-meta assoc :fulcro.client.primitives/computed computed-map))
      (cond-> props
        (not (empty? computed-map)) (assoc :fulcro.client.primitives/computed computed-map)))))

(defn get-computed
  "Return the computed properties on a component or its props."
  ([x]
   (get-computed x []))
  ([x k-or-ks]
   (when-not (nil? x)
     (let [props (cond-> x (component-instance? x) props)
           ks    (into [:fulcro.client.primitives/computed]
                   (cond-> k-or-ks
                     (not (sequential? k-or-ks)) vector))]
       (if (vector? props)
         (-> props meta (get-in ks))
         (get-in props ks))))))

(defn get-extra-props
  "Get any data (as a map) that extensions have associated with the given Fulcro component."
  [this]
  (isoget-in this [:props :fulcro$extra_props] {}))

(defn props
  "Return a components props."
  [component]
  (let [props-from-parent    (isoget-in component [:props :fulcro$value])
        computed-from-parent (get-computed props-from-parent)
        props-from-updates   (computed (isoget-in component [:state :fulcro$value]) computed-from-parent)]
    (newer-props props-from-parent props-from-updates)))

(defn children
  "Get the sequence of react children of the given component."
  [component]
  #?(:clj  (get-in component [:children])
     :cljs (gobj/getValueByKeys component "props" "children")))

(defn react-type
  "Returns the component type, regardless of whether the component has been
   mounted"
  [x]
  #?(:clj  (if (component-class? x) x (:fulcro$class x))
     :cljs (or (gobj/get x "type") (type x))))

(defn component-options
  ([this & ks]
   (let [c       (react-type this)
         options (or (isoget this :fulcro$options) (isoget c :fulcro$options))]
     (if (seq options)
       (get-in options (vec ks))
       options))))

(defn has-feature? #?(:cljs {:tag boolean}) [component option-key] (contains? (component-options component) option-key))
(defn has-initial-app-state? #?(:cljs {:tag boolean}) [component] (has-feature? component :initial-state))
(defn has-ident? #?(:cljs {:tag boolean}) [component] (has-feature? component :ident))
(defn has-query? #?(:cljs {:tag boolean}) [component] (has-feature? component :query))
(defn has-pre-merge? #?(:cljs {:tag boolean}) [component] (has-feature? component :pre-merge))
(defn ident [this props] (when (has-feature? this :ident) ((component-options this :ident) this props)))
(defn query [this] (when (has-feature? this :query) ((component-options this :query) this)))
(defn initial-state [clz params] (when (has-feature? clz :initial-state) ((component-options clz :initial-state) params)))
(defn pre-merge [this data] (when (has-feature? this :pre-merge) ((component-options this :pre-merge) data)))
(defn depth [this] (isoget-in this [:props :fulcro$depth]))

(defn get-raw-react-prop
  "GET a RAW react prop"
  [c k]
  (isoget-in c [:props k]))

(defn any->app
  "Attempt to coerce `x` to a reconciler.  Legal inputs are a fulcro application, reconciler, a mounted component, a
  map with a :reconciler key, or an atom holding any of the above."
  [x]
  (letfn [(fulcro-app? [x] (and (map? x) (contains? x :com.fulcrologic.fulcro.application/state-atom)))]
    (cond
      (component-instance? x) (get-raw-react-prop x :fulcro$app)
      (fulcro-app? x) x
      #?(:clj  (instance? IDeref x)
         :cljs (satisfies? IDeref x)) (any->app (deref x)))))

(defn raw->newest-props
  "Using raw react props/state returns the newest Fulcro props."
  [raw-props raw-state]
  #?(:clj  raw-props
     :cljs (let [next-props (gobj/get raw-props "fulcro$value")
                 opt-props  (gobj/get raw-state "fulcro$value")]
             (newer-props next-props opt-props))))

(defn shared
  "Return the global shared properties of the root. See :shared and
   :shared-fn reconciler options."
  ([component]
   (shared component []))
  ([component k-or-ks]
   {:pre [(component-instance? component)]}
   (let [shared (isoget-in component [:props :fulcro$shared])
         ks     (cond-> k-or-ks
                  (not (sequential? k-or-ks)) vector)]
     (cond-> shared
       (not (empty? ks)) (get-in ks)))))

(letfn
  [(wrap-props-state-handler
     ([handler]
      (wrap-props-state-handler handler true))
     ([handler check-for-fresh-props-in-state?]
      #?(:clj (fn [& args] (apply handler args))
         :cljs
              (fn [raw-props raw-state]
                (this-as this
                  (let [props (if check-for-fresh-props-in-state?
                                (raw->newest-props raw-props raw-state)
                                (gobj/get raw-props "fulcro$props"))
                        state (gobj/get raw-state "fulcro$state")]
                    (handler this props state)))))))
   (static-wrap-props-state-handler
     [handler]
     #?(:clj (fn [& args] (apply handler args))
        :cljs
        (fn [raw-props raw-state]
          (let [props (raw->newest-props raw-props raw-state)
                state (gobj/get raw-state "fulcro$state")]
            (handler props state)))))
   (should-component-update?
     [raw-next-props raw-next-state]
     #?(:clj true
        :cljs (if *blindly-render*
                true
                (this-as this
                  (let [current-props     (props this)
                        next-props        (raw->newest-props raw-next-props raw-next-state)
                        next-state        (gobj/get raw-next-state "fulcro$state")
                        current-state     (gobj/getValueByKeys this "state" "fulcro$state")
                        props-changed?    (not= current-props next-props)
                        state-changed?    (not= current-state next-state)
                        next-children     (gobj/get raw-next-props "children")
                        children-changed? (not= (gobj/getValueByKeys this "props" "children") next-children)]
                    (or props-changed? state-changed? children-changed?))))))
   (component-did-update
     [raw-prev-props raw-prev-state snapshot]
     #?(:cljs
        (this-as this
          (let [{:keys [ident componentDidUpdate]} (component-options this)
                prev-state (gobj/get raw-prev-state "fulcro$state")
                prev-props (raw->newest-props raw-prev-props raw-prev-state)]
            (when componentDidUpdate
              (componentDidUpdate this prev-props prev-state snapshot))
            (when ident
              (let [old-ident        (ident this prev-props)
                    next-ident       (ident this (props this))
                    app              (any->app this)
                    drop-component!  (ah/app-algorithm app :drop-component!)
                    index-component! (ah/app-algorithm app :index-component!)]
                (when (not= old-ident next-ident)
                  (drop-component! this old-ident)
                  (index-component! this))))))))
   (component-did-mount
     []
     #?(:cljs
        (this-as this
          (gobj/set this "fulcro$mounted" true)
          (let [{:keys [componentDidMount]} (component-options this)
                app              (any->app this)
                index-component! (ah/app-algorithm app :index-component!)]
            (index-component! this)
            (when componentDidMount
              (componentDidMount this))))))
   (component-will-unmount []
     #?(:cljs
        (this-as this
          (let [{:keys [componentWillUnmount]} (component-options this)
                app             (any->app this)
                drop-component! (ah/app-algorithm app :drop-component!)]
            (when componentWillUnmount
              (componentWillUnmount this))
            (gobj/set this "fulcro$mounted" false)
            (drop-component! this)))))
   (wrap-this
     [handler]
     #?(:clj (fn [& args] (apply handler args))
        :cljs
        (fn [& args] (this-as this (apply handler this args)))))
   (wrap-props-handler
     ([handler]
      (wrap-props-handler handler true))
     ([handler check-for-fresh-props-in-state?]
      #?(:clj #(handler %1)
         :cljs
              (fn [raw-props]
                (this-as this
                  (let [raw-state (.-state this)
                        props     (if check-for-fresh-props-in-state?
                                    (raw->newest-props raw-props raw-state)
                                    (gobj/get raw-props "fulcro$props"))]
                    (handler this props)))))))

   (wrap-base-render [render]
     #?(:clj (fn [& args]
               (binding [*parent* (first args)]
                 (apply render args)))
        :cljs
        (fn [& args]
          (this-as this
            (if-let [app (any->app this)]
              (binding [*app*         app
                        *depth*       (inc (depth this))
                        *shared*      (shared this)
                        *query-state* (-> app (:com.fulcrologic.fulcro.application/state-atom) deref)
                        *parent*      this]
                (apply render this args))
              (log/fatal "Cannot find app on component!"))))))]
  (defn configure-component!
    "Configure the given `cls` to act as a react component."
    [cls fqkw options]
    #?(:clj
       (let [name   (str/join "/" [(namespace fqkw) (name fqkw)])
             {:keys [render]} options
             result {::component-class?  true
                     :fulcro$options     (assoc options :render (wrap-base-render render))
                     :fulcro$registryKey fqkw
                     :displayName        name}]
         (register-component! fqkw result)
         result)
       :cljs
       ;; This user-supplied versions will expect `this` as first arg
       (let [{:keys [getDerivedStateFromProps shouldComponentUpdate getSnapshotBeforeUpdate render
                     initLocalState componentDidCatch getDerivedStateFromError
                     componentWillUpdate componentWillMount componentWillReceiveProps
                     UNSAFE_componentWillMount UNSAFE_componentWillUpdate UNSAFE_componentWillReceiveProps]} options
             name              (str/join "/" [(namespace fqkw) (name fqkw)])
             js-instance-props (clj->js
                                 (-> {:componentDidMount     component-did-mount
                                      :componentWillUnmount  component-will-unmount
                                      :componentDidUpdate    component-did-update
                                      :shouldComponentUpdate (if shouldComponentUpdate
                                                               (wrap-props-state-handler shouldComponentUpdate)
                                                               should-component-update?)
                                      :fulcro$isComponent    true
                                      :type                  cls
                                      :displayName           name}
                                   (cond->
                                     render (assoc :render (wrap-base-render render))
                                     getSnapshotBeforeUpdate (assoc :getSnapshotBeforeUpdate (wrap-props-state-handler getSnapshotBeforeUpdate))
                                     componentDidCatch (assoc :componentDidCatch (wrap-this componentDidCatch))
                                     UNSAFE_componentWillMount (assoc :UNSAFE_componentWillMount (wrap-this UNSAFE_componentWillMount))
                                     UNSAFE_componentWillUpdate (assoc :UNSAFE_componentWillUpdate (wrap-props-state-handler UNSAFE_componentWillUpdate))
                                     UNSAFE_componentWillReceiveProps (assoc :UNSAFE_componentWillReceiveProps (wrap-props-handler UNSAFE_componentWillReceiveProps))
                                     componentWillMount (assoc :componentWillMount (wrap-this componentWillMount))
                                     componentWillUpdate (assoc :componentWillUpdate (wrap-this componentWillUpdate))
                                     componentWillReceiveProps (assoc :componentWillReceiveProps (wrap-props-handler componentWillReceiveProps))
                                     initLocalState (assoc :initLocalState (wrap-this initLocalState)))))
             statics           (cond-> {:displayName            name
                                        :fulcro$class           cls
                                        :cljs$lang$type         true
                                        :cljs$lang$ctorStr      name
                                        :cljs$lang$ctorPrWriter (fn [_ writer _] (cljs.core/-write writer name))}
                                 getDerivedStateFromError (assoc :getDerivedStateFromError getDerivedStateFromError)
                                 getDerivedStateFromProps (assoc :getDerivedStateFromProps (static-wrap-props-state-handler getDerivedStateFromProps)))]
         (gobj/extend (.-prototype cls) js/React.Component.prototype js-instance-props
           #js {"fulcro$options" options})
         (gobj/extend cls (clj->js statics) #js {"fulcro$options" options})
         (gobj/set cls "fulcro$registryKey" fqkw)           ; done here instead of in extend (clj->js screws it up)
         (register-component! fqkw cls)))))

(defn mounted? [this]
  #?(:clj  false
     :cljs (gobj/get this "fulcro$mounted" false)))

(defn set-state!
  ([component new-state callback]
   #?(:clj
      (when-let [state-atom (:state component)]
        (swap! state-atom update merge new-state)
        (callback))
      :cljs
      (if (mounted? component)
        (.setState ^js component
          (fn [prev-state props]
            #js {"fulcro$state" (merge (gobj/get prev-state "fulcro$state") new-state)})
          callback))))
  ([component new-state]
   (set-state! component new-state nil)))

(defn get-state
  "Get a component's local state. May provide a single key or a sequential
   collection of keys for indexed access into the component's local state."
  ([component]
   (get-state component []))
  ([component k-or-ks]
   (let [cst #?(:clj (some-> component :state deref)
                :cljs (gobj/getValueByKeys component "state" "fulcro$state"))]
     (get-in cst (if (sequential? k-or-ks) k-or-ks [k-or-ks])))))

(let [update-fn (fn [component f args]
                  #?(:cljs (.setState component
                             (fn [prev-state props]
                               #js {"fulcro$state" (apply f (gobj/get prev-state "fulcro$state") args)}))))]
  (defn update-state!
    "Update a component's local state. Similar to Clojure(Script)'s swap!

    This function affects a managed cljs map maintained in React state.  If you want to affect the low-level
    js state itself use React's own `.setState` on the component."
    ([component f]
     (update-fn component f []))
    ([component f & args]
     (update-fn component f args))))

(defn get-initial-state
  "Get the initial state of a component. Needed because calling the protocol method from a defui component in clj will not work as expected."
  ([class]
   (some-> (initial-state class {}) (with-meta {:computed true})))
  ([class params]
   (some-> (initial-state class params) (with-meta {:computed true}))))

(defn computed-initial-state?
  "Returns true if the given initial state was computed from a call to get-initial-state."
  [s]
  (and (map? s) (some-> s meta :computed)))

(defn get-ident
  "Get the ident for a mounted component OR using a component class.

  That arity-2 will return the ident using the supplied props map.

  The single-arity version should only be used with a mounted component (e.g. `this` from `render`), and will derive the
  props that were sent to it most recently."
  ([x]
   {:pre [(component-instance? x)]}
   (if-let [m (props x)]
     (ident x m)
     (log/warn "get-ident was invoked on component with nil props (this could mean it wasn't yet mounted): " x)))
  ([class props]
   (if-let [id (ident class props)]
     (do
       (when-not (eql/ident? id)
         (log/warn "get-ident returned an invalid ident:" id (:displayName (component-options class))))
       (if (= :com.fulcrologic.fulcro.algorithms.merge/not-found (second id)) [(first id) nil] id))
     (do
       (log/warn "get-ident called with something that is either not a class or does not implement ident: " class)
       nil))))


(defn is-factory?
  [class-or-factory]
  (and (fn? class-or-factory)
    (-> class-or-factory meta (contains? :qualifier))))

(defn query-id
  "Returns a string ID for the query of the given class with qualifier"
  [class qualifier]
  (if (nil? class)
    (log/error "Query ID received no class (if you see this warning, it probably means metadata was lost on your query)" (ex-info "" {}))
    (when-let [classname (component-name class)]
      (str classname (when qualifier (str "$" qualifier))))))

(defn denormalize-query
  "Takes a state map that may contain normalized queries and a query ID. Returns the stored query or nil."
  [state-map ID]
  (let [get-stored-query (fn [id] (get-in state-map [::queries id :query]))]
    (when-let [normalized-query (get-stored-query ID)]
      (prewalk (fn [ele]
                 (if-let [q (and (string? ele) (get-stored-query ele))]
                   q
                   ele)) normalized-query))))

(defn- get-query-id
  "Get the query id that is cached in the component's props."
  [component]
  (get-raw-react-prop component #?(:clj  :fulcro$queryid
                                   :cljs "fulcro$queryid")))

(defn get-query-by-id [state-map class queryid]
  (let [query (or (denormalize-query state-map queryid) (query class))]
    (with-meta query {:component class
                      :queryid   queryid})))

(defn get-query
  "Get the query for the given class or factory. If called without a state map, then you'll get the declared static
  query of the class. If a state map is supplied, then the dynamically set queries in that state will result in
  the current dynamically-set query according to that state."
  ([class-or-factory] (get-query class-or-factory (or *query-state* {})))
  ([class-or-factory state-map]
   (when (nil? class-or-factory)
     (throw (ex-info "nil passed to get-query" {})))
   (binding [*query-state* state-map]
     (let [class     (cond
                       (is-factory? class-or-factory) (-> class-or-factory meta :class)
                       (component-instance? class-or-factory) (react-type class-or-factory)
                       :else class-or-factory)
           ;; Hot code reload. Avoid classes that were caches on metadata using the registry.
           class     (if #?(:cljs goog.DEBUG :clj false)
                       (-> class class->registry-key registry-key->class)
                       class)
           qualifier (if (is-factory? class-or-factory)
                       (-> class-or-factory meta :qualifier)
                       nil)
           queryid   (if (component-instance? class-or-factory)
                       (get-query-id class-or-factory)
                       (query-id class qualifier))]
       (when (and class (has-query? class))
         (get-query-by-id state-map class queryid))))))

(defn make-state-map
  "Build a component's initial state using the defsc initial-state-data from
  options, the children from options, and the params from the invocation of get-initial-state."
  [initial-state children-by-query-key params]
  (let [join-keys (set (keys children-by-query-key))
        init-keys (set (keys initial-state))
        is-child? (fn [k] (contains? join-keys k))
        value-of  (fn value-of* [[isk isv]]
                    (let [param-name    (fn [v] (and (keyword? v) (= "param" (namespace v)) (keyword (name v))))
                          substitute    (fn [ele] (if-let [k (param-name ele)]
                                                    (get params k)
                                                    ele))
                          param-key     (param-name isv)
                          param-exists? (contains? params param-key)
                          param-value   (get params param-key)
                          child-class   (get children-by-query-key isk)]
                      (cond
                        ; parameterized lookup with no value
                        (and param-key (not param-exists?)) nil

                        ; to-one join, where initial state is a map to be used as child initial state *parameters* (enforced by defsc macro)
                        ; and which may *contain* parameters
                        (and (map? isv) (is-child? isk)) [isk (get-initial-state child-class (into {} (keep value-of* isv)))]

                        ; not a join. Map is literal initial value.
                        (map? isv) [isk (into {} (keep value-of* isv))]

                        ; to-many join. elements MUST be parameters (enforced by defsc macro)
                        (and (vector? isv) (is-child? isk)) [isk (mapv (fn [m] (get-initial-state child-class (into {} (keep value-of* m)))) isv)]

                        ; to-many join. elements might be parameter maps or already-obtained initial-state
                        (and (vector? param-value) (is-child? isk)) [isk (mapv (fn [params]
                                                                                 (if (computed-initial-state? params)
                                                                                   params
                                                                                   (get-initial-state child-class params))) param-value)]

                        ; vector of non-children
                        (vector? isv) [isk (mapv (fn [ele] (substitute ele)) isv)]

                        ; to-one join with parameter. value might be params, or an already-obtained initial-state
                        (and param-key (is-child? isk) param-exists?) [isk (if (computed-initial-state? param-value)
                                                                             param-value
                                                                             (get-initial-state child-class param-value))]
                        param-key [isk param-value]
                        :else [isk isv])))]
    (into {} (keep value-of initial-state))))

(defn wrapped-render [this real-render]
  #?(:clj
     (real-render)
     :cljs
     (let [app               (gobj/getValueByKeys this "props" "fulcro$app")
           render-middleware (ah/app-algorithm app :render-middleware)]
       (if render-middleware
         (render-middleware this real-render)
         (real-render)))))

(defn- create-element
  "Create a react element for a Fulcro class.  In CLJ this returns the same thing as a mounted instance, whereas in CLJS it is an
  element (which has yet to instantiate an instance)."
  [class props children]
  #?(:clj
     (let [init-state (component-options class :initLocalState)
           state-atom (atom {})
           this       {::element?          true
                       :fulcro$isComponent true
                       :props              props
                       :children           children
                       :state              state-atom
                       :fulcro$class       class}
           state      (when init-state (init-state this))]
       (when (map? state)
         (reset! state-atom state))
       this)
     :cljs
     (apply js/React.createElement class props children)))

(defn factory
  "Create a factory constructor from a component class created with
   defui."
  ([class] (factory class nil))
  ([class {:keys [keyfn qualifier] :as opts}]
   (with-meta
     (fn element-factory [props & children]
       (let [key              (when-not (nil? keyfn) (keyfn props))
             ref              (:ref props)
             ref              (cond-> ref (keyword? ref) str)
             app              *app*
             props-middleware (ah/app-algorithm app :props-middleware)
             ;; Our data-readers.clj makes #js == identity in CLJ
             props            #js {:ref             ref
                                   :fulcro$reactKey key
                                   :fulcro$value    props
                                   :fulcro$queryid  (query-id class qualifier)
                                   :fulcro$app      *app*
                                   :fulcro$shared   *shared*
                                   :fulcro$parent   *parent*
                                   :fulcro$depth    *depth*}
             props            (if props-middleware
                                (props-middleware class props)
                                props)]
         #?(:cljs
            (when key
              (gobj/set props "key" key)))
         (create-element class props children)))
     {:class     class
      :queryid   (query-id class qualifier)
      :qualifier qualifier})))

(defn computed-factory
  "Similar to factory, but returns a function with the signature
  [props computed & children] instead of default [props & children].
  This makes easier to send computed."
  ([class] (computed-factory class {}))
  ([class options]
   (let [real-factory (factory class options)]
     (fn
       ([props] (real-factory props))
       ([props computed-props]
        (real-factory (computed props computed-props)))
       ([props computed-props & children]
        (apply real-factory (computed props computed-props) children))))))

(defn transact!
  "Submit a transaction for processing.

  The underlying transaction system is pluggable, but the default supported options are:

  - `:optimistic?` - boolean. Should the transaction be processed optimistically?
  - `:ref` - ident. The ident of the component used to submit this transaction. This is set automatically if you use a component to call this function.
  - `:component` - React element. Set automatically if you call this function using a component.
  - `:refresh` - Vector containing idents (of components) and keywords (of props). Things that have changed and should be re-rendered
    on screen. Only necessary when the underlying rendering algorithm won't auto-detect, such as when UI is derived from the
    state of other components or outside of the directly queried props. Interpretation depends on the renderer selected:
    The ident-optimized render treats these as \"extras\".
  - `:only-refresh` - Vector of idents/keywords.  If the underlying rendering configured algorithm supports it: The
    components using these are the *only* things that will be refreshed in the UI.
    This can be used to avoid the overhead of looking for stale data when you know exactly what
    you want to refresh on screen as an extra optimization. Idents are *not* checked against queries.
  - `:abort-id` - An ID (you make up) that makes it possible (if the plugins you're using support it) to cancel
    the network portion of the transaction (assuming it has not already completed).
  - `:compressible?` - boolean. Check compressible-transact! docs.

  NOTE: This function calls the application's `tx!` function (which is configurable). Fulcro 2 'follow-on reads' are
  supported by the default version and are added to the `:refresh` entries. Your choice of rendering algorithm will
  influence their necessity.

  Returns the transaction ID of the submitted transaction.
  "
  ([app-or-component tx options]
   (when-let [app (any->app app-or-component)]
     (let [tx!     (ah/app-algorithm app :tx!)
           options (cond-> options
                     (has-ident? app-or-component) (assoc :ref (get-ident app-or-component))
                     (component-instance? app-or-component) (assoc :component app-or-component))]
       (tx! app tx options))))
  ([app-or-comp tx]
   (transact! app-or-comp tx {})))

(declare normalize-query)

(defn link-element [element]
  (prewalk (fn link-element-helper [ele]
             (let [{:keys [queryid]} (meta ele)]
               (if queryid queryid ele))) element))

(defn normalize-query-elements
  "Determines if there are query elements in the present query that need to be normalized as well. If so, it does so.
  Returns the new state map."
  [state-map query]
  (reduce
    (fn normalize-query-elements-reducer [state ele]
      (try
        (let [parameterized? (seq? (log/spy :info ele))
              raw-element    (if parameterized? (first ele) ele)]
          (cond
            (util/union? raw-element) (let [union-alternates            (first (vals raw-element))
                                            union-meta                  (-> union-alternates meta)
                                            normalized-union-alternates (-> (into {} (map link-element union-alternates))
                                                                          (with-meta union-meta))
                                            union-query-id              (-> union-alternates meta :queryid)]
                                        (assert union-query-id "Union query has an ID. Did you use extended get-query?")
                                        (util/deep-merge
                                          {::queries {union-query-id {:query normalized-union-alternates
                                                                      :id    union-query-id}}}
                                          (reduce (fn normalize-union-reducer [s [_ subquery]]
                                                    (normalize-query s subquery)) state union-alternates)))
            (and
              (util/join? raw-element)
              (util/recursion? (util/join-value raw-element))) state
            (util/join? raw-element) (normalize-query state (util/join-value raw-element))
            :else state))
        (catch #?(:clj Exception :cljs :default) e
          (log/error e "Query normalization failed. Perhaps you tried to set a query with a syntax error?"))))
    state-map (log/spy :info query)))

(defn link-query
  "Find all of the elements (only at the top level) of the given query and replace them
  with their query ID"
  [query]
  (let [metadata (meta query)]
    (with-meta
      (mapv link-element query)
      metadata)))

(defn normalize-query
  "Given a state map and a query, returns a state map with the query normalized into the database. Query fragments
  that already appear in the state will not be added. "
  [state-map query]
  (let [new-state (normalize-query-elements state-map query)
        new-state (if (nil? (::queries new-state))
                    (assoc new-state ::queries {})
                    new-state)
        top-query (link-query query)]
    (if-let [queryid (some-> query meta :queryid)]
      (util/deep-merge {::queries {queryid {:query top-query :id queryid}}} new-state)
      new-state)))

(defn set-query*
  "Put a query in app state.
  NOTE: Indexes must be rebuilt after setting a query, so this function should primarily be used to build
  up an initial app state."
  [state-map class-or-factory {:keys [query] :as args}]
  (let [queryid   (cond
                    (nil? class-or-factory)
                    nil

                    (some-> class-or-factory meta (contains? :queryid))
                    (some-> class-or-factory meta :queryid)

                    :otherwise (query-id class-or-factory nil))
        component (or (-> class-or-factory meta :class) class-or-factory)
        setq*     (fn [state]
                    (normalize-query
                      (update state ::queries dissoc queryid)
                      (vary-meta query assoc :queryid queryid :component component)))]
    (if (string? queryid)
      (cond-> state-map
        (contains? args :query) (setq*))
      (do
        (log/error "Set query failed. There was no query ID. Use a class or factory for the second argument.")
        state-map))))

(defn set-query!
  "Set a dynamic query. Alters the query, and then rebuilds internal indexes.

  `x` is anything that any->app accepts."
  [x class-or-factory {:keys [query params] :as opts}]
  (let [app        (any->app x)
        state-atom (:com.fulcrologic.fulcro.application/state-atom app)
        queryid    (cond
                     (string? class-or-factory) class-or-factory
                     (some-> class-or-factory meta (contains? :queryid)) (some-> class-or-factory meta :queryid)
                     :otherwise (query-id class-or-factory nil))]
    (if (and (string? queryid) (or query params))
      (let [index-root!      (ah/app-algorithm app :index-root!)
            schedule-render! (ah/app-algorithm app :schedule-render!)]
        (swap! state-atom set-query* class-or-factory {:queryid queryid :query query :params params})
        (when index-root! (index-root! app))
        (when schedule-render! (schedule-render! app {:force-root? true})))
      (log/error "Unable to set query. Invalid arguments."))))

(defn get-indexes
  "Get the component indexes from a component instance or app. See also `ident->any`, `class->any`, etc."
  [x]
  (let [app (any->app x)]
    (some-> app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/indexes)))

(defn ident->components
  "Return all components for a given ident. `x` is anything any->app accepts."
  [x ident]
  (some-> (get-indexes x) :ident->components (get ident)))

(defn ident->any
  "Return a components that uses the given ident. `x` is anything any->app accepts."
  [x ident]
  (first (ident->components x ident)))

(defn prop->classes
  "Get all component classes that query for the given prop.
  `x` can be anything `any->app` is ok with.

  Returns all classes that query for that prop (or ident)"
  [x prop]
  (some-> (get-indexes x) :prop->classes (get prop)))

(defn class->all
  "Get all components from the indexes that are instances of the component class.
  `x` can be anything `any->app` is ok with."
  [x class]
  (let [k (class->registry-key class)]
    (some-> (get-indexes x) :class->components (get k))))

(defn class->any
  "Get any component from the indexes that are instances of the component class.
  `x` can be anything `any->app` is ok with."
  [x cls]
  (first (class->all x cls)))

(defn component->state-map
  "Returns the current value of the state map via a component instance. Note that it is not safe to render
  arbitrary data from the state map since Fulcro will have no idea that it should refresh a component that
  does so; however, it is sometimes useful to look at the state map for information that doesn't
  change over time."
  [this] (some-> this any->app :com.fulcrologic.fulcro.application/state-atom deref))

(defn wrap-update-extra-props
  "Wrap the props middleware such that `f` is called to get extra props that should be placed
  in the extra-props arg of the component.

  `handler` - (optional) The next item in the props middleware chain.
  `f` - A (fn [cls extra-props] new-extra-props)

  `f` will be passed the class being rendered and the current map of extra props. It should augment
  those and return a new version."
  ([f]
   (fn [cls raw-props]
     #?(:clj  (update raw-props :fulcro$extra_props (partial f cls))
        :cljs (let [existing (or (gobj/get raw-props "fulcro$extra_props") {})
                    new      (f cls existing)]
                (gobj/set raw-props "fulcro$extra_props" new)
                raw-props))))
  ([handler f]
   (fn [cls raw-props]
     #?(:clj  (let [props (update raw-props :fulcro$extra_props (partial f cls))]
                (handler cls props))
        :cljs (let [existing (or (gobj/get raw-props "fulcro$extra_props") {})
                    new      (f cls existing)]
                (gobj/set raw-props "fulcro$extra_props" new)
                (handler cls raw-props))))))

(defn fragment
  "Wraps children in a React.Fragment. Props are optional, like normal DOM elements."
  [& args]
  #?(:clj
     (let [optional-props (first args)
           props?         (and (instance? APersistentMap optional-props) (not (component-instance? optional-props)))
           [_ children] (if props?
                          [(first args) (rest args)]
                          [{} args])]
       (vec children))
     :cljs
     (let [[props children] (if (map? (first args))
                              [(first args) (rest args)]
                              [#js {} args])]
       (apply js/React.createElement js/React.Fragment (clj->js props) children))))

#?(:clj
   (defmacro with-parent-context
     "Wraps the given body with the correct internal bindings of the parent so that Fulcro internals
     will work when that body is embedded in unusual ways (e.g. as the body in a child-as-a-function
     React pattern)."
     [outer-parent & body]
     (if-not (:ns &env)
       `(do ~@body)
       `(let [parent# ~outer-parent
              r#      (or *app* (any->app parent#))
              d#      (or *depth* (inc (depth parent#)))
              s#      (or *shared* (shared parent#))
              p#      (or *parent* parent#)]
          (binding [*app*    r#
                    *depth*  d#
                    *shared* s#
                    *parent* p#]
            ~@body)))))

(defn ptransact!
  "
  DEPRECATED: Generally use `result-action` in mutations to chain sequences instead.

  Like `transact!`, but ensures each call completes (in a full-stack, pessimistic manner) before the next call starts
  in any way. Note that two calls of this function have no guaranteed relationship to each other. They could end up
  intermingled at runtime. The only guarantee is that for *a single call* to `ptransact!`, the calls in the given tx will run
  pessimistically (one at a time) in the order given. Follow-on reads in the given transaction will be repeated after each remote
  interaction.

  `component-or-app` a mounted component or the app
  `tx` the tx to run
  `ref` the ident (ref context) in which to run the transaction (including all deferrals)"
  ([component-or-app tx]
   (transact! component-or-app tx {:optimistic? false}))
  ([component-or-app ref tx]
   (transact! component-or-app tx {:optimistic? false
                                   :ref         ref})))

(defn compressible-transact!
  "Identical to `transact!`, but marks the history edge as compressible. This means that if more than one
  adjacent history transition edge is compressible, only the more recent of the sequence of them is kept. This
  is useful for things like form input fields, where storing every keystoke in history is undesirable. This
  also compress the transactions in Fulcro Inspect.

  NOTE: history events that trigger remote interactions are not compressible, since they may be needed for
  automatic network error recovery handling."
  ([comp-or-reconciler tx]
   (transact! comp-or-reconciler tx {:compressible? true}))
  ([comp-or-reconciler ref tx]
   (transact! comp-or-reconciler tx {:compressible? true
                                     :ref           ref})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEFSC MACRO SUPPORT. Most of this could be in a diff ns, but then hot code reload while working on the macro
;; does not work right.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (defn cljs? [env]
     (boolean (:ns env))))

#?(:clj
   (defn- is-link?
     "Returns true if the given query element is a link query like [:x '_]."
     [query-element] (and (vector? query-element)
                       (keyword? (first query-element))
                       ; need the double-quote because when in a macro we'll get the literal quote.
                       (#{''_ '_} (second query-element)))))

#?(:clj
   (defn -legal-keys
     "PRIVATE. Find the legal keys in a query. NOTE: This is at compile time, so the get-query calls are still embedded (thus cannot
     use the AST)"
     [query]
     (letfn [(keeper [ele]
               (cond
                 (list? ele) (recur (first ele))
                 (keyword? ele) ele
                 (is-link? ele) (first ele)
                 (and (map? ele) (keyword? (ffirst ele))) (ffirst ele)
                 (and (map? ele) (is-link? (ffirst ele))) (first (ffirst ele))
                 :else nil))]
       (set (keep keeper query)))))

#?(:clj
   (defn- children-by-prop
     [query]
     (into {}
       (keep #(if (and (map? %) (or (is-link? (ffirst %)) (keyword? (ffirst %))))
                (let [k   (if (vector? (ffirst %))
                            (first (ffirst %))
                            (ffirst %))
                      cls (-> % first second second)]
                  [k cls])
                nil) query))))

#?(:clj
   (defn- replace-and-validate-fn
     "Replace the first sym in a list (the function name) with the given symbol.

     env - the macro &env
     sym - The symbol that the lambda should have
     external-args - A sequence of arguments that the user should not include, but that you want to be inserted in the external-args by this function.
     user-arity - The number of external-args the user should supply (resulting user-arity is (count external-args) + user-arity).
     fn-form - The form to rewrite
     sym - The symbol to report in the error message (in case the rewrite uses a different target that the user knows)."
     ([env sym external-args user-arity fn-form] (replace-and-validate-fn env sym external-args user-arity fn-form sym))
     ([env sym external-args user-arity fn-form user-known-sym]
      (when-not (<= user-arity (count (second fn-form)))
        (throw (ana/error (merge env (meta fn-form)) (str "Invalid arity for " user-known-sym ". Expected " user-arity " or more."))))
      (let [user-args    (second fn-form)
            updated-args (into (vec (or external-args [])) user-args)
            body-forms   (drop 2 fn-form)]
        (->> body-forms
          (cons updated-args)
          (cons sym)
          (cons 'fn))))))

#?(:clj
   (defn- build-query-forms
     "Validate that the property destructuring and query make sense with each other."
     [env class thissym propargs {:keys [template method]}]
     (cond
       template
       (do
         (assert (or (symbol? propargs) (map? propargs)) "Property args must be a symbol or destructuring expression.")
         (let [to-keyword            (fn [s] (cond
                                               (nil? s) nil
                                               (keyword? s) s
                                               :otherwise (let [nspc (namespace s)
                                                                nm   (name s)]
                                                            (keyword nspc nm))))
               destructured-keywords (when (map? propargs) (util/destructured-keys propargs))
               queried-keywords      (-legal-keys template)
               has-wildcard?         (some #{'*} template)
               to-sym                (fn [k] (symbol (namespace k) (name k)))
               illegal-syms          (mapv to-sym (set/difference destructured-keywords queried-keywords))]
           (when (and (not has-wildcard?) (seq illegal-syms))
             (throw (ana/error (merge env (meta template)) (str "defsc " class ": " illegal-syms " was destructured in props, but does not appear in the :query!"))))
           `(~'fn ~'query* [~thissym] ~template)))
       method
       (replace-and-validate-fn env 'query* [thissym] 0 method))))

#?(:clj
   (defn- build-ident
     "Builds the ident form. If ident is a vector, then it generates the function and validates that the ID is
     in the query. Otherwise, if ident is of the form (ident [this props] ...) it simply generates the correct
     entry in defui without error checking."
     [env thissym propsarg {:keys [method template keyword]} is-legal-key?]
     (cond
       keyword (if (is-legal-key? keyword)
                 `(~'fn ~'ident* [~'_ ~'props] [~keyword (~keyword ~'props)])
                 (throw (ana/error (merge env (meta template)) (str "The table/id " keyword " of :ident does not appear in your :query"))))
       method (replace-and-validate-fn env 'ident* [thissym propsarg] 0 method)
       template (let [table   (first template)
                      id-prop (or (second template) :db/id)]
                  (cond
                    (nil? table) (throw (ana/error (merge env (meta template)) "TABLE part of ident template was nil" {}))
                    (not (is-legal-key? id-prop)) (throw (ana/error (merge env (meta template)) (str "The ID property " id-prop " of :ident does not appear in your :query")))
                    :otherwise `(~'fn ~'ident* [~'this ~'props] [~table (~id-prop ~'props)]))))))

#?(:clj
   (defn- build-render [classsym thissym propsym compsym extended-args-sym body]
     (let [computed-bindings (when compsym `[~compsym (com.fulcrologic.fulcro.components/get-computed ~thissym)])
           extended-bindings (when extended-args-sym `[~extended-args-sym (com.fulcrologic.fulcro.components/get-extra-props ~thissym)])
           render-fn         (symbol (str "render-" (name classsym)))]
       `(~'fn ~render-fn [~thissym]
          (com.fulcrologic.fulcro.components/wrapped-render ~thissym
            (fn []
              (let [~propsym (com.fulcrologic.fulcro.components/props ~thissym)
                    ~@computed-bindings
                    ~@extended-bindings]
                ~@body)))))))

#?(:clj
   (defn- build-and-validate-initial-state-map [env sym initial-state legal-keys children-by-query-key]
     (let [env           (merge env (meta initial-state))
           join-keys     (set (keys children-by-query-key))
           init-keys     (set (keys initial-state))
           illegal-keys  (if (set? legal-keys) (set/difference init-keys legal-keys) #{})
           is-child?     (fn [k] (contains? join-keys k))
           param-expr    (fn [v]
                           (if-let [kw (and (keyword? v) (= "param" (namespace v))
                                         (keyword (name v)))]
                             `(~kw ~'params)
                             v))
           parameterized (fn [init-map] (into {} (map (fn [[k v]] (if-let [expr (param-expr v)] [k expr] [k v])) init-map)))
           child-state   (fn [k]
                           (let [state-params    (get initial-state k)
                                 to-one?         (map? state-params)
                                 to-many?        (and (vector? state-params) (every? map? state-params))
                                 code?           (list? state-params)
                                 from-parameter? (and (keyword? state-params) (= "param" (namespace state-params)))
                                 child-class     (get children-by-query-key k)]
                             (when code?
                               (throw (ana/error env (str "defsc " sym ": Illegal parameters to :initial-state " state-params ". Use a lambda if you want to write code for initial state. Template mode for initial state requires simple maps (or vectors of maps) as parameters to children. See Developer's Guide."))))
                             (cond
                               (not (or from-parameter? to-many? to-one?)) (throw (ana/error env (str "Initial value for a child (" k ") must be a map or vector of maps!")))
                               to-one? `(com.fulcrologic.fulcro.components/get-initial-state ~child-class ~(parameterized state-params))
                               to-many? (mapv (fn [params]
                                                `(com.fulcrologic.fulcro.components/get-initial-state ~child-class ~(parameterized params)))
                                          state-params)
                               from-parameter? `(com.fulcrologic.fulcro.components/get-initial-state ~child-class ~(param-expr state-params))
                               :otherwise nil)))
           kv-pairs      (map (fn [k]
                                [k (if (is-child? k)
                                     (child-state k)
                                     (param-expr (get initial-state k)))]) init-keys)
           state-map     (into {} kv-pairs)]
       (when (seq illegal-keys)
         (throw (ana/error env (str "Initial state includes keys " illegal-keys ", but they are not in your query."))))
       `(~'fn ~'build-initial-state* [~'params] (com.fulcrologic.fulcro.components/make-state-map ~initial-state ~children-by-query-key ~'params)))))

#?(:clj
   (defn- build-raw-initial-state
     "Given an initial state form that is a list (function-form), simple copy it into the form needed by defui."
     [env method]
     (replace-and-validate-fn env 'build-raw-initial-state* [] 1 method)))

#?(:clj
   (defn- build-initial-state [env sym {:keys [template method]} legal-keys query-template-or-method]
     (when (and template (contains? query-template-or-method :method))
       (throw (ana/error (merge env (meta template)) (str "When query is a method, initial state MUST be as well."))))
     (cond
       method (build-raw-initial-state env method)
       template (let [query    (:template query-template-or-method)
                      children (or (children-by-prop query) {})]
                  (build-and-validate-initial-state-map env sym template legal-keys children)))))

#?(:clj
   (s/def ::ident (s/or :template (s/and vector? #(= 2 (count %))) :method list? :keyword keyword?)))
#?(:clj
   (s/def ::query (s/or :template vector? :method list?)))
#?(:clj
   (s/def ::initial-state (s/or :template map? :method list?)))
#?(:clj
   (s/def ::options (s/keys :opt-un [::query
                                     ::ident
                                     ::initial-state])))

#?(:clj
   (s/def ::args (s/cat
                   :sym symbol?
                   :doc (s/? string?)
                   :arglist (s/and vector? #(<= 2 (count %) 5))
                   :options (s/? map?)
                   :body (s/* any?))))

#?(:clj
   (defn defsc*
     [env args]
     (when-not (s/valid? ::args args)
       (throw (ana/error env (str "Invalid arguments. " (-> (s/explain-data ::args args)
                                                          ::s/problems
                                                          first
                                                          :path) " is invalid."))))
     (let [{:keys [sym doc arglist options body]} (s/conform ::args args)
           [thissym propsym computedsym extra-args] arglist
           _                                (when (and options (not (s/valid? ::options options)))
                                              (let [path    (-> (s/explain-data ::options options) ::s/problems first :path)
                                                    message (cond
                                                              (= path [:query :template]) "The query template only supports vectors as queries. Unions or expression require the lambda form."
                                                              (= :ident (first path)) "The ident must be a keyword, 2-vector, or lambda of no arguments."
                                                              :else "Invalid component options. Please check to make\nsure your query, ident, and initial state are correct.")]
                                                (throw (ana/error env message))))
           {:keys [ident query initial-state]} (s/conform ::options options)
           body                             (or body ['nil])
           ident-template-or-method         (into {} [ident]) ;clojure spec returns a map entry as a vector
           initial-state-template-or-method (into {} [initial-state])
           query-template-or-method         (into {} [query])
           validate-query?                  (and (:template query-template-or-method) (not (some #{'*} (:template query-template-or-method))))
           legal-key-checker                (if validate-query?
                                              (or (-legal-keys (:template query-template-or-method)) #{})
                                              (complement #{}))
           ident-form                       (build-ident env thissym propsym ident-template-or-method legal-key-checker)
           state-form                       (build-initial-state env sym initial-state-template-or-method legal-key-checker query-template-or-method)
           query-form                       (build-query-forms env sym thissym propsym query-template-or-method)
           render-form                      (build-render sym thissym propsym computedsym extra-args body)
           nspc                             (if (cljs? env) (-> env :ns :name str) (name (ns-name *ns*)))
           fqkw                             (keyword (str nspc) (name sym))
           options-map                      (cond-> options
                                              state-form (assoc :initial-state state-form)
                                              ident-form (assoc :ident ident-form)
                                              query-form (assoc :query query-form)
                                              render-form (assoc :render render-form))]
       (if (cljs? env)
         `(do
            (declare ~sym)
            (let [options# ~options-map]
              (defn ~(vary-meta sym assoc :doc doc :once true)
                [props#]
                (cljs.core/this-as this#
                  (if-let [init-state# (get options# :initLocalState)]
                    (set! (.-state this#) (cljs.core/js-obj "fulcro$state" (init-state# this# (goog.object/get props# "fulcro$value"))))
                    (set! (.-state this#) (cljs.core/js-obj "fulcro$state" {})))
                  nil))
              (com.fulcrologic.fulcro.components/configure-component! ~sym ~fqkw options#)))
         `(do
            (declare ~sym)
            (let [options# ~options-map]
              (def ~(vary-meta sym assoc :doc doc :once true)
                (com.fulcrologic.fulcro.components/configure-component! ~(str sym) ~fqkw options#))))))))

#?(:clj
   (defmacro ^{:doc      "Define a stateful component. This macro emits a React UI class with a query,
   optional ident (if :ident is specified in options), optional initial state, optional css, lifecycle methods,
   and a render method. It can also cause the class to implement additional protocols that you specify. Destructuring is
   supported in the argument list.

   The template (data-only) versions do not have any arguments in scope
   The lambda versions have arguments in scope that make sense for those lambdas, as listed below:

   ```
   (defsc Component [this {:keys [db/id x] :as props} {:keys [onSelect] :as computed} extended-args]
     {
      ;; stateful component options
      ;; query template is literal. Use the lambda if you have ident-joins or unions.
      :query [:db/id :x] ; OR (fn [] [:db/id :x]) ; this in scope
      ;; ident template is table name and ID property name
      :ident [:table/by-id :id] ; OR (fn [] [:table/by-id id]) ; this and props in scope
      ;; initial-state template is magic..see dev guide. Lambda version is normal.
      :initial-state {:x :param/x} ; OR (fn [params] {:x (:x params)}) ; nothing is in scope
      ;; pre-merge, use a lamba to modify new merged data with component needs
      :pre-merge (fn [{:keys [...]}] (merge {:ui/default-value :start} tree))

      ; React Lifecycle Methods (this in scope)
      :initLocalState            (fn [this props] ...) ; CAN BE used to call things as you might in a constructor. Return value is initial state.
      :shouldComponentUpdate     (fn [this next-props next-state] ...)

      :componentDidUpdate        (fn [this prev-props prev-state snapshot] ...) ; snapshot is optional, and is 16+. Is context for 15
      :componentDidMount         (fn [this] ...)
      :componentWillUnmount      (fn [this] ...)

      ;; DEPRECATED IN REACT 16 (to be removed in 17):
      :componentWillReceiveProps        (fn [this next-props] ...)
      :componentWillUpdate              (fn [this next-props next-state] ...)
      :componentWillMount               (fn [this] ...)

      ;; Replacements for deprecated methods in React 16.3+
      :UNSAFE_componentWillReceiveProps (fn [this next-props] ...)
      :UNSAFE_componentWillUpdate       (fn [this next-props next-state] ...)
      :UNSAFE_componentWillMount        (fn [this] ...)

      ;; ADDED for React 16:
      :componentDidCatch         (fn [this error info] ...)
      :getSnapshotBeforeUpdate   (fn [this prevProps prevState] ...)

      ;; static
      :getDerivedStateFromProps  (fn [props state] ...)

      ;; ADDED for React 16.6:
      :getDerivedStateFromError  (fn [error] ...)  **NOTE**: OVERWRITES entire state. This differs slightly from React.

      NOTE: shouldComponentUpdate should generally not be overridden other than to force it false so
      that other libraries can control the sub-dom. If you do want to implement it, then old props can
      be obtained from (prim/props this), and old state via (gobj/get (. this -state) \"fulcro$state\").

      ; BODY forms. May be omitted IFF there is an options map, in order to generate a component that is used only for queries/normalization.
      (dom/div #js {:onClick onSelect} x))
   ```

   NOTE: The options map is \"open\". That is: you can add whatever extra stuff you want to in order
   to co-locate data for component-related concerns. This is exactly what component-local css, the
   dynamic router, and form-state do.  The data that you add is available from `comp/component-options`
   on the component class and instances (i.e. `this`).

   See the Developer's Guide at book.fulcrologic.com for more details.
   "
               :arglists '([this dbprops computedprops]
                           [this dbprops computedprops extended-args])}
     defsc
     [& args]
     (try
       (defsc* &env args)
       (catch Exception e
         (if (contains? (ex-data e) :tag)

           (throw e)
           (throw (ana/error &env "Unexpected internal error while processing defsc. Please check your syntax." e)))))))

(defn force-children
  "Utility function that will force a lazy sequence of children (recursively) into realized
  vectors (React cannot deal with lazy seqs)"
  [x]
  (cond->> x
    (seq? x) (into [] (map force-children))))
