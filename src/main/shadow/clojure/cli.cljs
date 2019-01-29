(ns shadow.clojure.cli
  (:require
    [clojure.string :as str]
    ["which" :as which]
    ["child_process" :as cp]
    ["path" :as path]
    ["fs" :as fs]
    ["os" :as os]))

(def default-opts
  {:jvm-opts []
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
  (loop [m default-opts
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

        "-Sdeps"
        (-> m
            (update :deps-data conj (first more))
            (recur (rest more)))

        "-Scp"
        (-> m
            (assoc :force-cp (first more))
            (recur (rest more)))

        "-Spath"
        (-> m
            (assoc :print-classpath true)
            (recur more))

        "-Sverbose"
        (-> m
            (assoc :verbose true)
            (recur more))

        "-Sdescribe"
        (-> m
            (assoc :describe true)
            (recur more))

        "-Sforce"
        (-> m
            (assoc :force true)
            (recur more))

        "-Srepro"
        (-> m
            (assoc :repro true)
            (recur more))

        "-Stree"
        (-> m
            (assoc :tree true)
            (recur more))

        "-Spom"
        (-> m
            (assoc :pom true)
            (recur more))

        "-Sresolve-tags"
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


(defn last-modified [path]
  (let [s (fs/statSync path)]
    (-> s (.-mtime) (.getTime))))

(defn main [& args]
  (let [home-dir
        (path/resolve (os/homedir) ".clojure")

        tools-jar
        (path/resolve js/__dirname "clojure" "tools.jar")

        java-cmd
        (which/sync "java" #js {:nothrow true})

        config-files
        (->> [(path/resolve js/__dirname "clojure" "deps.edn")
              (path/resolve home-dir "deps.edn")
              (path/resolve "deps.edn")]
             (filter #(fs/existsSync %))
             (into []))

        ;; FIXME: should hash the files
        config-last-mod
        (->> config-files
             (map last-modified)
             (reduce #(js/Math.max %1 %2) 0))

        cache-dir
        (path/resolve ".cpcache")

        cache-prefix
        (str config-last-mod)

        libs-file
        (path/resolve cache-dir (str cache-prefix ".libs"))

        cp-file
        (path/resolve cache-dir (str cache-prefix ".cp"))

        jvm-file
        (path/resolve cache-dir (str cache-prefix ".jvm"))

        main-file
        (path/resolve cache-dir (str cache-prefix ".main"))

        make-cp-args
        ["-Xmx256m"
         "-classpath" tools-jar
         "clojure.main"
         "-m" "clojure.tools.deps.alpha.script.make-classpath"
         "--config-files" (str/join "," config-files)
         "--libs-file" libs-file
         "--cp-file" cp-file
         "--jvm-file" jvm-file
         "--main-file" main-file]

        opts
        (-> (parse-args args)
            (assoc :home-dir home-dir
                   :tools-jar tools-jar
                   :java-cmd java-cmd))]

    (when-not (fs/existsSync cp-file)
      (let [res (cp/spawnSync java-cmd (into-array make-cp-args) #js {:stdio "inherit"})]
        (assert (zero? (.-status res)))))

    (let [real-args
          (-> ["-cp" (str (fs/readFileSync cp-file))
               "clojure.main"]
              (into args))

          res (cp/spawnSync java-cmd (into-array real-args) #js {:stdio "inherit"})]

      (js/process.exit (.-status res))
      )))