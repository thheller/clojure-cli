(ns shadow.clojure.cli
  (:require
    [clojure.string :as str]
    [goog.object :as gobj]
    [cljs.pprint :refer (pprint)]
    ["which" :as which]
    ["child_process" :as cp]
    ["path" :as path]
    ["crypto" :as crypto]
    ["https" :as https]
    ["fs" :as fs]
    ["tar" :as tar]
    ["os" :as os]))

(def package-json (js/require "./package.json"))

(def script-version (gobj/get package-json "versions"))
(def tools-deps-version (gobj/get package-json "tools-deps-version"))

(def default-opts
  {:script-file js/__filename
   :script-version script-version
   :tools-deps-version tools-deps-version
   :install-url (str "https://download.clojure.org/install/clojure-tools-" tools-deps-version ".tar.gz")
   :jvm-opts []
   :resolve-aliases []
   :classpath-aliases []
   :jvm-aliases []
   :main-aliases []
   :all-aliases []
   :deps-data nil
   :args []
   :force-cp nil
   :print-classpath false
   :work-stack []})

(defn parse-args [args]
  (loop [m (assoc default-opts :input-args (vec args))
         [arg & more :as args] args]

    (if-not arg
      m
      (cond
        (str/starts-with? arg "-J")
        (-> m
            (update :jvm-opts conj (subs arg 2))
            (recur more))

        (str/starts-with? arg "-R")
        (-> m
            (update :resolve-aliases conj (subs arg 2))
            (recur more))

        (str/starts-with? arg "-C")
        (-> m
            (update :classpath-aliases conj (subs arg 2))
            (recur more))

        (str/starts-with? arg "-O")
        (-> m
            (update :jvm-aliases conj (subs arg 2))
            (recur more))

        (str/starts-with? arg "-M")
        (-> m
            (update :main-aliases conj (subs arg 2))
            (recur more))

        (str/starts-with? arg "-A")
        (-> m
            (update :all-aliases conj (subs arg 2))
            (recur more))

        (= "-Sdeps" arg)
        (-> m
            (assoc :deps-data (first more))
            (recur (rest more)))

        (= "-Scp" arg)
        (-> m
            (assoc :force-cp (first more))
            (recur (rest more)))

        (= "-Spath" arg)
        (-> m
            (assoc :print-classpath true)
            (recur more))

        (= "-Sverbose" arg)
        (-> m
            (assoc :verbose true)
            (recur more))

        (= "-Sdescribe" arg)
        (-> m
            (assoc :describe true)
            (recur more))

        (= "-Sforce" arg)
        (-> m
            (assoc :force true)
            (recur more))

        (= "-Srepro" arg)
        (-> m
            (assoc :repro true)
            (recur more))

        (= "-Stree" arg)
        (-> m
            (assoc :tree true)
            (recur more))

        (= "-Spom" arg)
        (-> m
            (assoc :pom true)
            (recur more))

        (= "-Sresolve-tags" arg)
        (-> m
            (assoc :resolve-tags true)
            (recur more))

        (str/starts-with? arg "-S")
        (throw (ex-info (str "Invalid option: " arg) {:tag ::invalid-option :arg arg}))

        (contains? #{"-h" "--help" "-?"} arg)
        (-> m
            (assoc :help true)
            (recur more))

        :else
        (-> m
            (update :args conj arg)
            (recur more))))))

(defn ensure-dir [dir]
  (when-not (fs/existsSync dir)
    (let [parent (path/resolve dir "..")]
      (when (and parent (not= parent dir))
        (ensure-dir parent)))
    (fs/mkdirSync dir)))

(defn last-modified [path]
  (let [s (fs/statSync path)]
    (-> s (.-mtime) (.getTime))))

(defn work-push [opts task-fn]
  (update opts :work-stack conj task-fn))

(defn work-next [{:keys [work-stack] :as opts}]
  (let [work-fn (peek work-stack)]
    (-> opts
        (assoc :work-stack (pop work-stack))
        (work-fn))))

(defn with-tools-jar
  [{:keys [resolve-tags config-dir install-dir tools-jar java-cmd] :as state}]
  (cond
    resolve-tags
    (prn :resolve-tags)

    :else
    (let [system-deps-file
          (path/resolve config-dir "deps.edn")

          ;; if ~/.clojure/deps.edn doesn't exist copy example-deps.edn
          _ (when-not (fs/existsSync system-deps-file)
              (fs/writeFileSync
                system-deps-file
                (fs/readFileSync (path/resolve install-dir "example-deps.edn"))))

          local-deps-file
          (path/resolve "deps.edn")

          install-deps-file
          (path/resolve install-dir "deps.edn")

          config-files
          (->> (if (:repro state)
                 [install-deps-file
                  local-deps-file]
                 [install-deps-file
                  system-deps-file
                  local-deps-file])
               (filter #(fs/existsSync %))
               (into []))

          state
          (assoc state
            :config-dir config-dir
            :system-deps-file system-deps-file
            :install-deps-file install-deps-file
            :local-deps-file local-deps-file
            :config-files config-files)

          ;; if local deps.edn exists if local .cpcache directory
          cache-dir
          (if (fs/existsSync local-deps-file)
            (path/resolve ".cpcache")
            ;; otherwise store in system paths
            (if-let [cfg js/process.env.CLJ_CACHE]
              (path/resolve cfg)
              (if-let [cfg js/process.env.XDG_CACHE_HOME]
                (path/resolve cfg "clojure")
                (path/resolve config-dir ".cpcache"))))

          _ (ensure-dir cache-dir)

          {:keys [resolve-aliases
                  classpath-aliases
                  jvm-aliases
                  main-aliases
                  all-aliases
                  deps-data]}
          state

          ;; FIXME: just update hash directly, no need for this string concat
          cache-data
          (str "|" tools-deps-version
               "|" script-version
               (str/join "" resolve-aliases)
               "|" (str/join "" classpath-aliases)
               "|" (str/join "" all-aliases)
               "|" (str/join "" jvm-aliases)
               "|" (str/join "" main-aliases)
               "|" deps-data
               "|" (str/join "|" config-files)
               "|" (->> config-files
                        (map last-modified)
                        (str/join "|")))

          cache-sig
          (-> (crypto/createHash "md5")
              (.update cache-data "utf8")
              (.digest "hex"))

          libs-file
          (path/resolve cache-dir (str cache-sig ".libs"))

          cp-file
          (path/resolve cache-dir (str cache-sig ".cp"))

          jvm-file
          (path/resolve cache-dir (str cache-sig ".jvm"))

          main-file
          (path/resolve cache-dir (str cache-sig ".main"))

          opts
          (assoc state
            :cache-dir cache-dir
            :main-file main-file
            :jvm-file jvm-file
            :cp-file cp-file
            :cache-sig cache-sig
            :libs-file libs-file)]

      (when (or (not (fs/existsSync cp-file))
                (:force opts))
        (println "Building classpath ...")
        (let [add-aliases
              (fn [opts flag aliases]
                (if-not (seq aliases)
                  opts
                  (conj opts (str flag (str/join "" aliases)))))

              make-cp-args
              (-> ["-Xmx256m"
                   "-classpath" tools-jar
                   "clojure.main"
                   "-m" "clojure.tools.deps.alpha.script.make-classpath"
                   "--config-files" (str/join "," config-files)
                   "--libs-file" libs-file
                   "--cp-file" cp-file
                   "--jvm-file" jvm-file
                   "--main-file" main-file]
                  (add-aliases "-R" resolve-aliases)
                  (add-aliases "-C" classpath-aliases)
                  (add-aliases "-J" jvm-aliases)
                  (add-aliases "-M" main-aliases)
                  (add-aliases "-A" all-aliases)
                  (cond->
                    (seq deps-data)
                    (conj "--config-data" deps-data)
                    (:force-cp state)
                    (conj "--skip-cp")))

              res
              (cp/spawnSync java-cmd (into-array make-cp-args) #js {:stdio "inherit"})]

          (when-not (zero? (.-status res))
            (println "Failed to build classpath.")
            (js/process.exit 1))
          (assert (zero? (.-status res)))))

      (let [{:keys [args]} opts

            real-args
            (-> ["-cp" (str (fs/readFileSync cp-file))
                 "clojure.main"]
                (into args))

            res (cp/spawnSync java-cmd (into-array real-args) #js {:stdio "inherit"})]

        (when-not (zero? (.-status res))
          (prn real-args)
          (prn res))

        (js/process.exit (.-status res))
        ))))

(defn make-progress-bar [completed total]
  (let [progress (/ completed total)
        pct (.toFixed (* progress 100) 1)]
    ;; FIXME: make actual [##########        ] 51% bar or format bytes better
    (str completed "/" total " - " pct "%")))

(defn unpack-download [{:keys [install-dir download-file] :as state}]
  (tar/x #js {:file download-file
              :cwd install-dir
              :strip 1 ;; .tar.gz has inner clojure-tools/ dir which we don't care about
              :gzip true
              :sync true
              :strict true})

  (fs/unlinkSync download-file)

  (-> state
      (dissoc :download-file)
      (work-next)))

(defn handle-download-response [{:keys [install-dir] :as state} ^js req ^js res]
  (let [status (.-statusCode res)
        headers (.-headers res)]
    (if (not= 200 status)
      (do (println "Download failed, returned status: " status)
          (js/process.exit 2))

      (let [content-length (js/parseInt (gobj/get headers "content-length") 10)
            downloaded-ref (atom 0)

            download-file
            (path/resolve install-dir "download.tar.gz")

            download-stream
            (fs/createWriteStream download-file)]

        (println "Downloading to:" download-file)

        (.on res "data"
          (fn [^js buf]
            (let [bytes (.-length buf)]
              (.write download-stream buf)
              (swap! downloaded-ref + bytes)
              (when js/process.stdout.isTTY
                (js/process.stdout.write (str "\rDownload Progress: " (make-progress-bar @downloaded-ref content-length))))
              )))

        (.on res "end"
          (fn []
            (.end download-stream
              (fn []
                ;; add extra spaces to ensure clearing progress
                (when js/process.stdout.isTTY
                  (js/process.stdout.write (str "\rDownload completed. Unpacking ...                                \n")))

                (-> state
                    (assoc :download-file download-file)
                    (work-push unpack-download)
                    (work-next))))))))))

(defn download-tools-jar [{:keys [install-url] :as state}]
  (println "Downloading:" install-url)
  (let [req (https/get install-url #js {})]
    (.on req "error"
      (fn [err]
        (prn [:download-error err])
        (js/process.exit 2)))

    (.on req "response"
      (fn [res]
        (handle-download-response state req res)
        ))))

(defn ensure-install [{:keys [tools-jar] :as state}]
  (-> state
      (cond->
        (not (fs/existsSync tools-jar))
        (work-push download-tools-jar))
      (work-next)))

(defn main [& args]
  (let [java-cmd (which/sync "java" #js {:nothrow true})]
    ;; FIXME: look for JAVA_HOME java not on path
    (if-not java-cmd
      (do (println "Couldn't find java executable.")
          (js/process.exit 1))

      (let [{:keys [help] :as state} (parse-args args)]
        (if help
          (println "HELP!")
          (let [config-dir
                (if-let [cfg js/process.env.CLJ_CONFIG]
                  (path/resolve cfg)
                  (if-let [cfg js/process.env.XDG_CONFIG_HOME]
                    (path/resolve cfg "clojure")
                    (path/resolve (os/homedir) ".clojure")))

                install-dir
                (path/resolve config-dir "install" tools-deps-version)

                tools-jar
                (path/resolve install-dir (str "clojure-tools-" tools-deps-version ".jar"))

                state
                (assoc state
                  :java-cmd java-cmd
                  :install-dir install-dir
                  :tools-jar tools-jar
                  :config-dir config-dir)]

            (ensure-dir config-dir)
            (ensure-dir install-dir)

            (-> state
                (work-push with-tools-jar)
                (ensure-install))))))))