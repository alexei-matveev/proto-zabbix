(ns proto-zabbix.queue
    (:import [java.util.concurrent LinkedBlockingDeque]))

;; See e.g. https://gist.github.com/mjg123/1305115
(defn make-queue []
  (LinkedBlockingDeque.))

;; Puts an  object to the back  of queue. By local  convention putting
;; the  sentinel  value  into   the  queue  will  signal  termination.
;; Consumers will put  the sentinel value back as there  might be more
;; than  one consumer.   The sentinel  value is  nil.  However  as the
;; LinkedBlockingDeque  cannot handle  that  we use  the queue  object
;; itself internally.
(defn offer! [^LinkedBlockingDeque q x]
  (if-not (nil? x)
    (.offer q x)
    (.offer q q)))

;; Takes from  the front of  queue.  If  queue is empty,  blocks until
;; something is offered into it.  Returns nil if the sentinel value is
;; detected. The caller does not need  to put the sentinel value back,
;; we take care of that here.
(defn take! [^LinkedBlockingDeque q]
  (let [x (.take q)]
    (if-not (= x q)
      x
      (do
        (offer! q q)
        nil))))
