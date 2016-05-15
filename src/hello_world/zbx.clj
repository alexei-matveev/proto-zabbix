(ns hello-world.zbx
  (:require [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import [java.io StringWriter]
           [java.nio ByteBuffer ByteOrder]
           [java.net Socket]))

(defn- read-byte-array [reader n]
  (let [buf (byte-array n)
        m (.read reader buf)]
    (assert (= m n))
    buf))

;; Zabbix headers provides the size field in little endian. Java data
;; streams use big endian unconditionally.  ByteBuffer offers a
;; workaround:
(defn- read-long [reader]
  (let [buf (read-byte-array reader 8)]
    (-> (ByteBuffer/wrap buf)
        (.order ByteOrder/LITTLE_ENDIAN)
        (.getLong))))

(defn- read-json [reader]
  (with-open [response (StringWriter.)]
    (io/copy reader response)
    (-> response
        str
        (json/parse-string))))

(defn send-request
  "Sends an TCP request to the specified host and port"
  [host port text]
  (with-open [sock (Socket. host port)
              writer (io/output-stream sock)
              reader (io/input-stream sock)]
    (.write writer (.getBytes text))
    (.flush writer)
    ;; Response is "ZBXD\1" <8 byte length> <json body>
    (let [magic (read-byte-array reader 4)
          ;; Single byte version number, always 1:
          version (.read reader)
          ;; You could wrap the reader into a DataInputStream but Java
          ;; would assume big endian with (.readLong reader)
          length (read-long reader)
          json (read-json reader)]
      (vector magic version length json))))


;; (send-request "localhost" 10050 "vfs.fs.discovery")

