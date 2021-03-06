;;
;; See also:
;;
;; - https://github.com/hengyunabc/zabbix-sender/
;;
(ns proto-zabbix.proto
  (:require [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.io InputStream OutputStream ByteArrayOutputStream]
           [java.net Socket]))

;; If no  byte is available  because the stream is  at the end  of the
;; file, the  value -1 is  returned; otherwise,  at least one  byte is
;; read and  stored into buf. The  EOF happens e.g. when  the agent is
;; not allowed to reply to the IP that invoked zabbix-get:
(defn- read-byte-array ^bytes [^InputStream stream n]
  (let [buf (byte-array n)
        m (.read stream buf)]
    (when (= -1 m)
      (throw (ex-info "End of stream." {:n n})))
    (assert (= m n))
    buf))

;; Zabbix headers provide the size field in little endian. Java data
;; streams use big endian unconditionally.  ByteBuffer offers a
;; workaround:
(defn- buf->long [buf]
  (-> (ByteBuffer/wrap buf)
      (.order ByteOrder/LITTLE_ENDIAN)
      (.getLong)))

(defn- long->buf ^bytes [n]
  (let [buf (byte-array 8)]             ; Long/BYTES >= Java 8
    (-> (ByteBuffer/wrap buf)
        (.order ByteOrder/LITTLE_ENDIAN)
        (.putLong n)
        (.array))))

;; (buf->long (long->buf 1234567890)) => 1234567890

(defn- read-long [stream]
  (let [buf (read-byte-array stream 8)] ; Long/BYTES >= Java 8
    (buf->long buf)))

(defn- write-long [^OutputStream stream n]
  (let [buf (long->buf n)]
    (.write stream buf)))

;;
;; Note that while reading the JSON you cannot wait on EOF. Instead
;; read the fixed length and parse it already. Reading until the end
;; of stream is waiting for the timeout (a few seconds).
;;
;; FIXME: returns original text on parse errors!
;;
;; See e.g.:
;;
;; https://www.zabbix.com/documentation/2.4/manual/appendix/items/activepassive
;;
(defn- read-json [stream length]
  (let [buf (read-byte-array stream length)
        text (String. buf)]
    (try
        (json/parse-string text)
        ;; FIXME: JsonParseException maybe?
        (catch Exception e text))))


;;
;; Protocoll is "ZBXD\1" <8 byte length> <json body>. This protocoll
;; is used both by agent and server when putting data on the wire.
;;
(defn- proto-read [^InputStream stream]
  (let [magic (String. (read-byte-array stream 4))
        ;; Single byte version number, always 1:
        version (.read stream)
        ;; You could wrap the stream into a DataInputStream but Java
        ;; would assume big endian with (.readLong stream). Instead:
        length (read-long stream)
        ;; For unsupported keys the body is ZBX_NOTSUPPORTED, without
        ;; quotes, which is not a valid json. FIXME: we return
        ;; original text on ANY parse error so far. This makes parse
        ;; errors indistinguishable from quoted strings:
        json (read-json stream length)]
    (assert (= "ZBXD" magic))
    (assert (= 1 version))
    json))

;; Each small write to a stream is a TCP round trip. When looking
;; at tcpdump the vanilla Zabbix agents sends magic+version, size and
;; the body in three different rounds, the vanilla server is slightly
;; less chatty. We write everything in one step ...
(defn- proto-write [^OutputStream stream json]
  (let [text (json/generate-string json)
        body (.getBytes text)
        length (count body)
        magic+version (.getBytes "ZBXD\1")]
    (doto (ByteArrayOutputStream.)
      (.write magic+version)
      (write-long length)
      (.write body)
      (.writeTo stream))
    (.flush stream)))

;; (def a1 {"request" "active checks", "host" "Zabbix server"})
;; (proto-write (io/output-stream "a1") a1)
;; (proto-read (io/input-stream "a1"))

(defn proto-recv [socket]
  ;; Dont close the socket here if you are going to send
  ;; replies. Using with-open instead of let would do that:
  (let [stream (io/input-stream socket)]
    (proto-read stream)))

(defn proto-send [socket json]
  (let [stream (io/output-stream socket)]
    (proto-write stream json)))

;; Send JSON and receive JSON in response. Active agent does this
;; combo a lot.
(defn send-recv [socket json]
  (proto-send socket json)
  (proto-recv socket))

;;
;; Examples of valid key values as text [1]:
;;
;;     agent.version
;;     vfs.fs.discovery
;;     vfs.fs.size[/,used]
;;
;; [1] https://www.zabbix.com/documentation/2.4/manual/appendix/items/supported_by_platform
;;
(defn zabbix-get
  "Sends an TCP request to the specified host and port"
  [^String host ^long port ^String text]
  (with-open [sock (Socket. host port)
              writer (io/output-stream sock)
              reader (io/input-stream sock)]
    (.write writer (.getBytes text))
    (.flush writer)
    ;; Response is "ZBXD\1" <8 byte length> <json body>
    (proto-read reader)))

;;
;; [1] https://www.zabbix.org/wiki/Docs/protocols/zabbix_sender/2.0
;;
(defn zabbix-sender
  "Example on sending item data to the server. The data is supposed to
  be JSON with host-, key-, and value fields"
  [^String host ^long port data]
  ;; FIXME: contrary to what the wiki docs suggest, zabbix_sender does
  ;; not send clock if that was not supplied with each datum. So
  ;; maybe we should not either?
  (let [millis (System/currentTimeMillis)
        clock (quot millis 1000)
        request {:request "sender data",
                 :data data,
                 :clock clock}]
    (with-open [sock (Socket. host port)]
      (send-recv sock request))))

;; For your C-x C-e pleasure:
(comment
  (zabbix-get "localhost" 10050 "agent.version")
  (zabbix-get "localhost" 10050 "vfs.fs.size[/,used]")
  (zabbix-get "localhost" 10050 "vfs.fs.discovery")
  (zabbix-sender "localhost"
                 10051
                 [{:host "some host",
                   :key "my.key",
                   :value 42}])
  #_end)
