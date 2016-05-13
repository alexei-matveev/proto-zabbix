(ns hello-world.zbx
  (:require [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import [java.io StringWriter]
           [java.net Socket]))

(defn- read-char-array [reader n]
  (let [buf (char-array n)]
    (.read reader buf)
    buf))

(defn- read-json [reader]
  (with-open [response (StringWriter.)]
    (io/copy reader response)
    (-> response
        str
        (json/parse-string true))))

(defn send-request
  "Sends an TCP request to the specified host and port"
  [host port text]
  (with-open [sock (Socket. host port)
              writer (io/writer sock)
              reader (io/reader sock)]
    (.append writer text)
    (.flush writer)
    ;; Response is "ZBXD\1" <8 byte length> <json body>
    (let [header (read-char-array reader 5)
          length (read-char-array reader 8)
          json (read-json reader)]
      (vector header length json))))


;; (send-request "localhost" 10050 "vfs.fs.discovery")

