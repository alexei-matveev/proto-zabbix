(defproject f0bec0d/proto-zabbix "0.3.0-SNAPSHOT"
  :description "Zabbix Protocols in Clojure"
  :url "https://github.com/alexei-matveev/proto-zabbix"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [cheshire "5.10.0"]
                 ;; Für Zabbix API Abfragen:
                 [http-kit "2.5.0"]]
  ;; lein run will start an emulator of a Zabbix server:
  :main proto-zabbix.server
  :profiles {:uberjar {:aot :all}}
  :repositories [["snapshots" "file:///media-library/maven-repo/"]
                 ["releases" "file:///media-library/maven-repo/"]]
  ;;
  ;; Emit  warnings on  all reflection  calls.  The  native-image from
  ;; GraalVM toolbox, for example, will refuse to compile reflection:
  :global-vars {*warn-on-reflection* true}
  :target-path "target/%s")
