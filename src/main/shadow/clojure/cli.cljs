(ns shadow.clojure.cli
  (:require
    [clojure.string :as str]
    [cljs.pprint :refer (pprint)]
    ["which" :as which]
    ["child_process" :as cp]
    ["path" :as path]
    ["crypto" :as crypto]
    ["fs" :as fs]
    ["os" :as os]))

(def default-opts
  {:script-file js/__filename
   :script-version (.-version (js/require "./package.json"))
   :jvm-opts []
   :resolve-aliases []
   :classpath-aliases []
   :jvm-aliases []
   :main-aliases []
   :all-aliases []
   :deps-data []
   :args []
   :force-cp nil
   :print-classpath false})

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
            (update :deps-data conj (first more))
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

(defn main [& args]
  (let [java-cmd (which/sync "java" #js {:nothrow true})]
    ;; FIXME: look for JAVA_HOME java not on path
    (if-not java-cmd
      (do (println "Couldn't find java executable.")
          (js/process.exit 1))

      (let [{:keys [help resolve-tags] :as opts}
            (parse-args args)

            config-dir
            (if-let [cfg js/process.env.CLJ_CONFIG]
              (path/resolve cfg)
              (if-let [cfg js/process.env.XDG_CONFIG_HOME]
                (path/resolve cfg "clojure")
                (path/resolve (os/homedir) ".clojure")))

            _ (ensure-dir config-dir)

            tools-jar
            (path/resolve js/__dirname "clojure" "tools.jar")

            system-deps-file
            (path/resolve config-dir "deps.edn")

            ;; if ~/.clojure/deps.edn doesn't exist copy example-deps.edn
            _ (when-not (fs/existsSync system-deps-file)
                (fs/writeFileSync
                  system-deps-file
                  (fs/readFileSync (path/resolve js/__dirname "clojure" "example-deps.edn"))))

            local-deps-file
            (path/resolve "deps.edn")

            install-deps-file
            (path/resolve js/__dirname "clojure" "deps.edn")

            config-files
            (->> (if (:repro opts)
                   [install-deps-file
                    local-deps-file]
                   [install-deps-file
                    system-deps-file
                    local-deps-file])
                 (filter #(fs/existsSync %))
                 (into []))]

        (cond
          help
          (println "HELP!")

          resolve-tags
          (prn :resolve-tags)

          :else
          (let [;; if local deps.edn exists if local .cpcache directory
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

                cache-data
                (str (str/join "" (:resolve-aliases opts))
                     "|" (str/join "" (:classpath-aliases opts))
                     "|" (str/join "" (:all-aliases opts))
                     "|" (str/join "" (:jvm-aliases opts))
                     "|" (str/join "" (:main-aliases opts))
                     "|" (str/join "|" (:deps-data opts))
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
                (assoc opts
                  :cache-dir cache-dir
                  :main-file main-file
                  :jvm-file jvm-file
                  :cp-file cp-file
                  :cache-sig cache-sig
                  :libs-file libs-file)

                make-cp-args
                ["-Xmx256m"
                 "-classpath" tools-jar
                 "clojure.main"
                 "-m" "clojure.tools.deps.alpha.script.make-classpath"
                 "--config-files" (str/join "," config-files)
                 "--libs-file" libs-file
                 "--cp-file" cp-file
                 "--jvm-file" jvm-file
                 "--main-file" main-file]]

            (pprint opts)

            (when (or (not (fs/existsSync cp-file))
                      (:force opts))
              (let [res (cp/spawnSync java-cmd (into-array make-cp-args) #js {:stdio "inherit"})]
                (prn [:res res])
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

              (js/process.exit (.-status res))
              )))))))