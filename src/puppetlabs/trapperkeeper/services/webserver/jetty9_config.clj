(ns puppetlabs.trapperkeeper.services.webserver.jetty9-config
  (:import [java.security KeyStore]
           (java.io FileInputStream)
           (org.eclipse.jetty.server.handler RequestLogHandler)
           (ch.qos.logback.access.jetty RequestLogImpl)
           (org.eclipse.jetty.server Server)
           (org.codehaus.janino ScriptEvaluator)
           (org.codehaus.commons.compiler CompileException)
           (java.lang.reflect InvocationTargetException))
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [schema.core :as schema]
            [puppetlabs.ssl-utils.core :as ssl]
            [puppetlabs.kitchensink.core :refer [missing? num-cpus uuid parse-bool]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants / Defaults
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;; NOTE: We are making a decisive move away from overriding Jetty's
;;; implicit default values for settings when downstream TK apps do not
;;; explicitly provide values for them.  Please see the comments/tests in
;;; `jetty9_default_config_test.clj` for full details.
;;;
;;; Below we are making a handful of deliberate exceptions to this rule,
;;; but please do not perpetuate this pattern without a compelling reason to
;;; do so.


;;;
;;; Host/port settings
;;;
;;; These are really common and fairly benign, and removing them would probably
;;; only serve to make the bare configuration more onerous.
;;;
(def default-http-port 8080)
(def default-https-port 8081)
(def default-host "localhost")

;;;
;;; Security-related settings
;;;
;;; After some discussion, we decided that it was probably still appropriate to
;;; override Jetty's defaults for these security-related settings.  In the event
;;; that a vulnerability like "POODLE" is announced (where we needed to remove
;;; the SSLv3 protocol from the list of allowed protocols), we would need to do
;;; a release of tk-j9 to address it no matter what.  The choices would then be
;;; to update our own defaults for security-related settings, or, if we're not
;;; imposing our own defaults, to try to upgrade to a new version of Jetty where
;;; their implicit defaults reflect the security issue.  The latter is far more
;;; risky for our downstream apps, thus it was decided that it makes sense to
;;; keep these overrides.
;;;
;;; Also note that w/rt the default list of acceptable ciphers, we're deliberately
;;; excluding all diffie-helman ciphers due to some old JDK bugs:
;;;
;;; http://bugs.java.com/bugdatabase/view_bug.do?bug_id=8014618
;;; https://github.com/puppetlabs/puppetdb/commit/03e020dc85b83d6c83c9992ca6bd14f57e8fc91a
;;;
;;; These have been fixed in recent versions of the JDK, and it would be nice
;;; to be able to add the DH ciphers back in at some point, but we can't do that
;;; until we're certain that our minimum supported JDK versions for all of our
;;; supported distros will contain the relevant fixes.
;;;
(def acceptable-ciphers
  ["TLS_RSA_WITH_AES_256_CBC_SHA256"
   "TLS_RSA_WITH_AES_256_CBC_SHA"
   "TLS_RSA_WITH_AES_128_CBC_SHA256"
   "TLS_RSA_WITH_AES_128_CBC_SHA"
   "SSL_RSA_WITH_RC4_128_SHA"
   "SSL_RSA_WITH_3DES_EDE_CBC_SHA"
   "SSL_RSA_WITH_RC4_128_MD5"])
(def default-protocols ["TLSv1" "TLSv1.1" "TLSv1.2"])
(def default-client-auth :need)


;; TODO: these two need to be addressed in our upcoming work around
;; acceptors/selectors.  See TK-148.
(def default-max-threads 100)
(def default-queue-max-size (Integer/MAX_VALUE))

(def default-jmx-enable "true")
(def default-request-header-buffer-size 8192)
(def default-request-header-size 8192)

(def default-so-linger-in-milliseconds
  "The default SO_LINGER time to set on the ServerConnector in milliseconds.
  A value less than 0 indicates that SO_LINGER should be disabled."
  -1)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def StaticContent
  {:resource                          schema/Str
   :path                              schema/Str
   (schema/optional-key :follow-links) schema/Bool})

(def WebserverRawConfig
  {(schema/optional-key :port)                       schema/Int
   (schema/optional-key :host)                       schema/Str
   (schema/optional-key :max-threads)                schema/Int
   (schema/optional-key :queue-max-size)             schema/Int
   (schema/optional-key :request-header-max-size)    schema/Int
   (schema/optional-key :so-linger-seconds)          schema/Int
   (schema/optional-key :idle-timeout-milliseconds)  schema/Int
   (schema/optional-key :ssl-port)                   schema/Int
   (schema/optional-key :ssl-host)                   schema/Str
   (schema/optional-key :ssl-key)                    schema/Str
   (schema/optional-key :ssl-cert)                   schema/Str
   (schema/optional-key :ssl-cert-chain)             schema/Str
   (schema/optional-key :ssl-ca-cert)                schema/Str
   (schema/optional-key :keystore)                   schema/Str
   (schema/optional-key :truststore)                 schema/Str
   (schema/optional-key :key-password)               schema/Str
   (schema/optional-key :trust-password)             schema/Str
   (schema/optional-key :cipher-suites)              (schema/either schema/Str [schema/Str])
   (schema/optional-key :ssl-protocols)              (schema/either schema/Str [schema/Str])
   (schema/optional-key :client-auth)                schema/Str
   (schema/optional-key :ssl-crl-path)               schema/Str
   (schema/optional-key :jmx-enable)                 schema/Str
   (schema/optional-key :default-server)             schema/Bool
   (schema/optional-key :static-content)             [StaticContent]
   (schema/optional-key :gzip-enable)                schema/Bool
   (schema/optional-key :access-log-config)          schema/Str
   (schema/optional-key :shutdown-timeout-seconds)   schema/Int
   (schema/optional-key :post-config-script)         schema/Str})

(def MultiWebserverRawConfigUnvalidated
  {schema/Keyword  WebserverRawConfig})

(defn one-default?
  [config]
  (->> config
       vals
       (filter :default-server)
       count
       (>= 1)))

(defn map-of-maps? [x]
  (and (map? x)
       (every? map? (vals x))))

(def MultiWebserverRawConfig
  (schema/both MultiWebserverRawConfigUnvalidated (schema/pred one-default? 'one-default?)))

(def WebserverServiceRawConfig
  (schema/conditional
     map-of-maps? MultiWebserverRawConfig
     :else WebserverRawConfig))

(def WebserverSslPemConfig
  {:ssl-key                              schema/Str
   :ssl-cert                             schema/Str
   (schema/optional-key :ssl-cert-chain) schema/Str
   :ssl-ca-cert                          schema/Str})

(def WebserverSslKeystoreConfig
  {:keystore        KeyStore
   :key-password    schema/Str
   :truststore      KeyStore
   (schema/optional-key :trust-password) schema/Str})

(def WebserverSslClientAuth
  (schema/enum :need :want :none))

(def WebserverConnectorCommon
  {:request-header-max-size schema/Int
   :so-linger-milliseconds  schema/Int
   :idle-timeout-milliseconds (schema/maybe schema/Int)})

(def WebserverConnector
  (merge WebserverConnectorCommon
         {:host schema/Str
          :port schema/Int}))

(def WebserverSslContextFactory
  {:keystore-config                    WebserverSslKeystoreConfig
   :client-auth                        WebserverSslClientAuth
   (schema/optional-key :ssl-crl-path) (schema/maybe schema/Str)
   :cipher-suites                      [schema/Str]
   :protocols                          (schema/maybe [schema/Str])})

(def WebserverSslConnector
  (merge
    WebserverConnector
    WebserverSslContextFactory))

(def HasConnector
  (schema/either
    (schema/pred #(contains? % :http) 'has-http-connector?)
    (schema/pred #(contains? % :https) 'has-https-connector?)))

(def WebserverConfig
  (schema/both
    HasConnector
    {(schema/optional-key :http)  WebserverConnector
     (schema/optional-key :https) WebserverSslConnector
     :max-threads                 schema/Int
     :queue-max-size              schema/Int
     :jmx-enable                  schema/Bool}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Conversion functions (raw config -> schema)

(schema/defn ^:always-validate
  maybe-get-pem-config! :- (schema/maybe WebserverSslPemConfig)
  [config :- WebserverRawConfig]
  (let [pem-required-keys [:ssl-key :ssl-cert :ssl-ca-cert]
        pem-config (select-keys config pem-required-keys)]
    (condp = (count pem-config)
      3 (if-let [ssl-cert-chain (:ssl-cert-chain config)]
          (assoc pem-config :ssl-cert-chain ssl-cert-chain)
          pem-config)
      0 nil
      (throw (IllegalArgumentException.
               (format "Found SSL config options: %s; If configuring SSL from PEM files, you must provide all of the following options: %s"
                       (keys pem-config) pem-required-keys))))))

(schema/defn ^:always-validate
  get-x509s-from-ssl-cert-pem :- (schema/pred ssl/certificate-list?)
  [ssl-cert :- schema/Str
   ssl-cert-chain :- (schema/maybe schema/Str)]
  (if-not (fs/readable? ssl-cert)
    (throw (IllegalArgumentException.
             (format "Unable to open 'ssl-cert' file: %s"
                     ssl-cert))))
  (let [certs (ssl/pem->certs ssl-cert)]
    (if (= 0 (count certs))
      (throw (Exception.
               (format "No certs found in 'ssl-cert' file: %s"
                       ssl-cert))))
    (if ssl-cert-chain
      [(first certs)]
      certs)))

(schema/defn ^:always-validate
  get-x509s-from-ssl-cert-chain-pem :- (schema/pred ssl/certificate-list?)
  [ssl-cert-chain :- (schema/maybe schema/Str)]
  (if ssl-cert-chain
    (do
      (if-not (fs/readable? ssl-cert-chain)
        (throw (IllegalArgumentException.
                 (format "Unable to open 'ssl-cert-chain' file: %s"
                         ssl-cert-chain))))
      (ssl/pem->certs ssl-cert-chain))
    []))

(schema/defn ^:always-validate
  construct-ssl-x509-cert-chain :- (schema/pred ssl/certificate-list?)
  [ssl-cert :- schema/Str
   ssl-cert-chain :- (schema/maybe schema/Str)]
  (let [ssl-cert-x509s       (get-x509s-from-ssl-cert-pem ssl-cert ssl-cert-chain)
        ssl-cert-chain-x509s (get-x509s-from-ssl-cert-chain-pem ssl-cert-chain)]
    (into [] (concat ssl-cert-x509s ssl-cert-chain-x509s))))

(schema/defn ^:always-validate
  pem-ssl-config->keystore-ssl-config :- WebserverSslKeystoreConfig
  [{:keys [ssl-ca-cert ssl-key ssl-cert ssl-cert-chain]} :- WebserverSslPemConfig]
  (let [key-password   (uuid)
        ssl-x509-chain (construct-ssl-x509-cert-chain ssl-cert
                                                      ssl-cert-chain)]
    {:truststore    (-> (ssl/keystore)
                        (ssl/assoc-certs-from-file!
                          "CA Certificate" ssl-ca-cert))
     :key-password  key-password
     :keystore      (-> (ssl/keystore)
                        (ssl/assoc-private-key!
                          "Private Key"
                          (ssl/pem->private-key ssl-key)
                          key-password
                          ssl-x509-chain))}))

(schema/defn ^:always-validate
  warn-if-keystore-ssl-configs-found!
  [config :- WebserverRawConfig]
  (let [keystore-ssl-config-keys [:keystore :truststore :key-password :trust-password]
        keystore-ssl-config (select-keys config keystore-ssl-config-keys)]
    (when (pos? (count keystore-ssl-config))
      (log/warn (format "Found settings for both keystore-based and PEM-based SSL; using PEM-based settings, ignoring %s"
                        (keys keystore-ssl-config))))))

(schema/defn ^:always-validate
  get-jks-keystore-config! :- WebserverSslKeystoreConfig
  [{:keys [truststore keystore key-password trust-password]}
      :- WebserverRawConfig]
  (when (some nil? [truststore keystore key-password trust-password])
    (throw (IllegalArgumentException.
             (str "Missing some SSL configuration; must provide either :ssl-cert, "
                  ":ssl-key, and :ssl-ca-cert, OR :truststore, :trust-password, "
                  ":keystore, and :key-password."))))
  {:keystore       (doto (ssl/keystore)
                     (.load (FileInputStream. keystore)
                            (.toCharArray key-password)))
   :truststore     (doto (ssl/keystore)
                     (.load (FileInputStream. truststore)
                            (.toCharArray trust-password)))
   :key-password   key-password
   :trust-password trust-password})

(schema/defn ^:always-validate
  get-keystore-config! :- WebserverSslKeystoreConfig
  [config :- WebserverRawConfig]
  (if-let [pem-config (maybe-get-pem-config! config)]
    (do
      (warn-if-keystore-ssl-configs-found! config)
      (pem-ssl-config->keystore-ssl-config pem-config))
    (get-jks-keystore-config! config)))

(schema/defn ^:always-validate
  get-client-auth! :- WebserverSslClientAuth
  [config :- WebserverRawConfig]
  (let [client-auth (:client-auth config)]
    (cond
      (nil? client-auth) default-client-auth
      (contains? #{"need" "want" "none"} client-auth) (keyword client-auth)
      :else (throw
              (IllegalArgumentException.
                (format
                  "Unexpected value found for client auth config option: %s.  Expected need, want, or none."
                  client-auth))))))

(schema/defn ^:always-validate
  get-ssl-crl-path! :- (schema/maybe schema/Str)
  [config :- WebserverRawConfig]
  (if-let [ssl-crl-path (:ssl-crl-path config)]
    (if (fs/readable? ssl-crl-path)
      ssl-crl-path
      (throw (IllegalArgumentException.
               (format
                 "Non-readable path specified for ssl-crl-path option: %s"
                 ssl-crl-path))))))

(schema/defn get-or-parse-sequential-config-value :- [schema/Str]
  "Some config values can be entered as either a vector of strings or
   a single comma-separated string. Get the value for the given config
   key, parsing it into a seq if it's a string, or returning a default
   if it's not provided."
  [config :- WebserverRawConfig
   key :- schema/Keyword
   default :- [schema/Str]]
  (let [value (key config)]
    (cond
     (string? value) (map str/trim (str/split value #","))
     value value
     :else default)))

(defn get-cipher-suites-config [config]
  (get-or-parse-sequential-config-value config :cipher-suites acceptable-ciphers))

(defn get-ssl-protocols-config [config]
  (get-or-parse-sequential-config-value config :ssl-protocols default-protocols))

(schema/defn ^:always-validate
  contains-keys? :- schema/Bool
  [config :- WebserverRawConfig
   keys   :- #{schema/Keyword}]
  (boolean (some #(contains? config %) keys)))

(defn contains-http-connector? [config]
  (contains-keys? config #{:port :host}))

(schema/defn ^:always-validate
  so-linger-in-milliseconds :- schema/Int
  [config :- WebserverRawConfig]
  (if-let [linger-from-config (:so-linger-seconds config)]
    (* 1000 linger-from-config)
    default-so-linger-in-milliseconds))

(schema/defn ^:always-validate
  common-connector-config :- WebserverConnectorCommon
  [config :- WebserverRawConfig]
  {:request-header-max-size   (or (:request-header-max-size config)
                                  default-request-header-size)
   :so-linger-milliseconds    (so-linger-in-milliseconds config)
   :idle-timeout-milliseconds (:idle-timeout-milliseconds config)})

(schema/defn ^:always-validate
  maybe-get-http-connector :- (schema/maybe WebserverConnector)
  [config :- WebserverRawConfig]
  (if (contains-http-connector? config)
    (merge (common-connector-config config)
           {:host (or (:host config) default-host)
            :port (or (:port config) default-http-port)})))

(schema/defn ^:always-validate
  contains-https-connector? :- schema/Bool
  [config :- WebserverRawConfig]
  (contains-keys? config #{:ssl-port :ssl-host}))

(schema/defn ^:always-validate
  maybe-get-https-connector :- (schema/maybe WebserverSslConnector)
  [config :- WebserverRawConfig]
  (if (contains-https-connector? config)
    (merge (common-connector-config config)
           {:host                    (or (:ssl-host config) default-host)
            :port                    (or (:ssl-port config) default-https-port)
            :keystore-config         (get-keystore-config! config)
            :cipher-suites           (get-cipher-suites-config config)
            :protocols               (get-ssl-protocols-config config)
            :client-auth             (get-client-auth! config)
            :ssl-crl-path            (get-ssl-crl-path! config)})))

(schema/defn ^:always-validate
  maybe-add-http-connector :- {(schema/optional-key :http) WebserverConnector
                               schema/Keyword              schema/Any}
  [acc config :- WebserverRawConfig]
  (if-let [http-connector (maybe-get-http-connector config)]
    (assoc acc :http http-connector)
    acc))

(schema/defn ^:always-validate
  maybe-add-https-connector :- {(schema/optional-key :https) WebserverSslConnector
                                schema/Keyword              schema/Any}
  [acc config :- WebserverRawConfig]
  (if-let [https-connector (maybe-get-https-connector config)]
    (assoc acc :https https-connector)
    acc))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private helper functions

(defn validate-config
  [config]
  (when-not (one-default? config)
    (throw (IllegalArgumentException.
             "Error: More than one default server specified in configuration"))))

(defn selectors-count
  "The number of selector threads that should be allocated per connector per
  core.  This algorithm duplicates the default that Jetty 9.2.10.v20150310 uses.
  See: https://github.com/eclipse/jetty.project/blob/jetty-9.2.10.v20150310/jetty-server/src/main/java/org/eclipse/jetty/server/ServerConnector.java#L229"
  [num-cpus]
  (max 1 (min 4 (int (/ num-cpus 2)))))

(defn acceptors-count
  "The number of acceptor threads that should be allocated per connector per
  core.  This algorithm duplicates the default that Jetty 9.2.10.v20150310 uses.
  See: https://github.com/eclipse/jetty.project/blob/jetty-9.2.10.v20150310/jetty-server/src/main/java/org/eclipse/jetty/server/AbstractConnector.java#L190"
  [num-cpus]
  (max 1 (min 4 (int (/ num-cpus 8)))))

(defn threads-per-connector
  "The total number of threads needed per attached connector."
  [num-cpus]
  (+ (acceptors-count num-cpus)
     (selectors-count num-cpus)))

(schema/defn ^:always-validate
  connector-count :- schema/Int
  "Return the number of connectors found in the config."
  [config :- WebserverRawConfig]
  (let [connectors [(contains-http-connector? config)
                    (contains-https-connector? config)]]
    (count (filter true? connectors))))

(schema/defn ^:always-validate
  calculate-required-threads :- schema/Int
  "Calculate the number threads needed to operate based on the number of cores
  available and the number of connectors present.  This algorithm duplicates
  the default that Jetty 9.2.10.v20150310 uses.  See:
  https://github.com/eclipse/jetty.project/blob/jetty-9.2.10.v20150310/jetty-server/src/main/java/org/eclipse/jetty/server/Server.java#L334-L350"
  [config   :- WebserverRawConfig
   num-cpus :- schema/Int]
  (+ 1 (* (connector-count config)
          (threads-per-connector num-cpus))))

(schema/defn ^:always-validate
  determine-max-threads :- schema/Int
  "Determine the size of the Jetty thread pool. If the size is specified in the
  config then use that value, otherwise determine the minimum number of threads
  needed to operate. If that number is larger than the default then use it,
  otherwise use the default."
  [config   :- WebserverRawConfig
   num-cpus :- schema/Int]
  (let [max-threads (or (:max-threads config)
                        (max (calculate-required-threads config num-cpus)
                             default-max-threads))]
    (log/debugf "Using webserver thread pool size of %d" max-threads)
    max-threads))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  process-config :- WebserverConfig
  [config :- WebserverRawConfig]
  (let [result (-> {}
                   (maybe-add-http-connector config)
                   (maybe-add-https-connector config)
                   (assoc :max-threads (determine-max-threads config (num-cpus)))
                   (assoc :queue-max-size (get config :queue-max-size default-queue-max-size))
                   (assoc :jmx-enable (parse-bool (get config :jmx-enable default-jmx-enable))))]
    (when-not (some #(contains? result %) [:http :https])
      (throw (IllegalArgumentException.
               "Either host, port, ssl-host, or ssl-port must be specified on the config in order for the server to be started")))
    result))

(schema/defn ^:always-validate
  init-log-handler :- RequestLogHandler
  [config :- WebserverRawConfig]
  (let [handler (RequestLogHandler.)
        logger (RequestLogImpl.)]
    (.setFileName logger (:access-log-config config))
    (.setQuiet logger true)
    (.setRequestLog handler logger)
    handler))

(defn maybe-init-log-handler
  [config]
  (if (:access-log-config config)
    (init-log-handler config)))

(schema/defn ^:always-validate
  execute-post-config-script!
  [s :- Server
   script :- schema/Str]
  (log/warn (str "The 'post-config-script' setting is for advanced use cases only, "
                 "and may be subject to minor changes when the application is upgraded."))
  (let [script-err-msg "Invalid script string in webserver 'post-config-script' configuration"]
    (try
      (let [evaluator (doto (ScriptEvaluator.)
                        (.setParameters (into-array String ["server"])
                                        (into-array Class [Server]))
                        (.cook script))]
        (.evaluate evaluator (into-array Object [s])))
      (catch CompileException ex
        (throw (IllegalArgumentException. script-err-msg ex)))
      (catch InvocationTargetException ex
        (throw (IllegalArgumentException. script-err-msg ex))))))
