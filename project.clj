(defproject f0bec0d/proto-zabbix "0.1.0-SNAPSHOT"
  :description "Experiments with Zabbix protocol"
  :url "https://github.com/alexei-matveev/proto-zabbix"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [cheshire "5.8.0"]]
  ;; lein run will start an emulator of a Zabbix server:
  :main proto-zabbix.server
  :profiles {:uberjar {:aot :all}}
  ;;
  ;; Emit  warnings on  all reflection  calls.  The  native-image from
  ;; GraalVM toolbox, for example, will refuse to compile reflection:
  :global-vars {*warn-on-reflection* true}
  :target-path "target/%s")
