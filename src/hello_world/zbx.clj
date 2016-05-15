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

;; FIXME: returns original text on parse errors:
;;
;; See e.g.:
;;
;; https://www.zabbix.com/documentation/2.4\
;; /manual/appendix/items/activepassive
;;
(defn- read-json [reader]
  (with-open [response (StringWriter.)]
    (io/copy reader response)
    (let [text (str response)]
      ;; (prn (count text))
      (try
        (json/parse-string text)
        ;; FIXME: JsonParseException maybe?
        (catch Exception e text)))))

(defn- read-zbxd [reader]
  ;; Response is "ZBXD\1" <8 byte length> <json body>
  (let [magic (String. (read-byte-array reader 4))
        ;; Single byte version number, always 1:
        version (.read reader)
        ;; You could wrap the reader into a DataInputStream but Java
        ;; would assume big endian with (.readLong reader). Instead:
        length (read-long reader)
        ;; For unsupported keys the body is "ZBX_NOTSUPPORTED" which
        ;; is not a valid json. FIXME: we return original text on
        ;; ANY parse error so far. This makes parse errors
        ;; indistinguishable from quoted strings:
        json (read-json reader)]
    {:magic magic, :version version, :length length, :json json}))

;;
;; Examples of valid key values as text:
;;
;; agent.version
;; vfs.fs.discovery
;; vfs.fs.size[/,used]
;;
;; See e.g.:
;; https://www.zabbix.com/documentation/2.4\
;; /manual/appendix/items/supported_by_platform
;;
(defn zabbix-get
  "Sends an TCP request to the specified host and port"
  [host port text]
  (with-open [sock (Socket. host port)
              writer (io/output-stream sock)
              reader (io/input-stream sock)]
    (.write writer (.getBytes text))
    (.flush writer)
    ;; Response is "ZBXD\1" <8 byte length> <json body>
    (read-zbxd reader)))


;; (zabbix-get "localhost" 10050 "vfs.fs.discovery")
;; (zabbix-get "localhost" 10050 "vfs.fs.size[/,used]")
;; (zabbix-get "localhost" 10050 "agent.version")


