(ns clj-pgp.message
  "The functions in this namespace package raw data into PGP _messages_, which
  can be compressed, encrypted, and signed.

  The encryption functions use the concept of _encryptors_ and _decryptors_.
  A collection of encryptors may be used to encipher a message, and any
  corresponding decryptor will be able to decipher it.

  For symmetric encryption, the encryptor is the passphrase string and the
  corresponding decryptor is the same string.

  For public-key encryption, the encryptor is the public-key object and the
  decryptor is the corresponding private-key. Alternately, the decryptor can be
  a function which accepts a key id and returns the corresponding private-key,
  to look it up or unlock the key on demand."
  (:require
    [byte-streams :as bytes]
    [clojure.java.io :as io]
    (clj-pgp
      [core :as pgp]
      [tags :as tags]
      [util :refer [arg-coll arg-map]]))
  (:import
    (java.io
      ByteArrayOutputStream
      FilterOutputStream
      InputStream
      OutputStream)
    java.nio.ByteBuffer
    java.security.SecureRandom
    java.util.Date
    (org.bouncycastle.bcpg
      ArmoredOutputStream)
    (org.bouncycastle.openpgp
      PGPPBEEncryptedData
      PGPCompressedData
      PGPCompressedDataGenerator
      PGPEncryptedData
      PGPEncryptedDataGenerator
      PGPEncryptedDataList
      PGPLiteralData
      PGPLiteralDataGenerator
      PGPObjectFactory
      PGPPublicKeyEncryptedData
      PGPUtil)
    (org.bouncycastle.openpgp.operator.bc
      BcPGPDataEncryptorBuilder
      BcPBEDataDecryptorFactory
      BcPBEKeyEncryptionMethodGenerator
      BcPGPDigestCalculatorProvider
      BcPublicKeyDataDecryptorFactory
      BcPublicKeyKeyEncryptionMethodGenerator)))


;; ## PGP Data Encoding

(defprotocol ^:no-doc MessagePacket
  "Protocol for packets of message data."

  (reduce-message
    [data acc r-fn opts]
    "Recursively unpacks a message packet and calls `r-fn` with `acc` the packet itself.
    See `reduce-messages` for options")

  (can-decrypt?
    [data opts]
    "Determines if the message packet can be decrypted"))


(defn- reduce-content
  "Decodes an sequence of PGP objects from an input stream. unpacking each
  objects data.
  See `reduce-messages` for options"
  [^InputStream input acc r-fn opts]
  (reduce
    #(reduce-message %2 %1 r-fn opts)
    acc
    (pgp/read-objects input)))


(defn armored-data-stream
  "Wraps an `OutputStream` with an armored data stream. Packets written to this
  stream will be output in ASCII encoded Base64."
  ^OutputStream
  [^OutputStream output]
  (ArmoredOutputStream. output))



;; ## Literal Data Packets

(def data-formats
  "Supported data formats which can be specified when building literal data
  packets."
  {:binary PGPLiteralData/BINARY
   :text   PGPLiteralData/TEXT
   :utf8   PGPLiteralData/UTF8})


(defn literal-data-stream
  "Wraps an `OutputStream` with a literal data generator, returning another
  stream. Typically, the wrapped stream is a compressed data stream or
  encrypted data stream.

  Data written to the returned stream will write a literal data packet to the
  wrapped output stream. If the data is longer than the buffer size, the packet
  is written in chunks in a streaming fashion.

  Options may be provided to customize the packet:

  - `:buffer-size` maximum number of bytes per chunk
  - `:format`      data format type
  - `:filename`    filename string for the data
  - `:mtime`       data modification time"
  ^OutputStream
  [^OutputStream output & opts]
  (let [{:keys [buffer-size format filename ^Date mtime]
         :or {buffer-size 4096
              format      :binary
              filename    PGPLiteralData/CONSOLE
              mtime       (Date.)}}
        (arg-map opts)]
    (.open (PGPLiteralDataGenerator.)
           output
           (char (data-formats format))
           (str filename)
           mtime
           (byte-array buffer-size))))


;; Read the literal data bytes from the packet.
(extend-protocol MessagePacket
  PGPLiteralData

  (reduce-message
    [packet acc r-fn opts]
    (let [data (.getInputStream packet)
          format (tags/code->tag data-formats (char (.getFormat packet)))]
      (r-fn acc {:format format
                 :filename (.getFileName packet)
                 :mtime (.getModificationTime packet)
                 :data data})))

  (can-decrypt?
    [packet opts]
    ;; This is NOOP as this data is already decrypted
    true))



;; ## Compressed Data Packets

(defn compressed-data-stream
  "Wraps an `OutputStream` with a compressed data generator, returning another
  stream. Typically, literal data packets will be written to this stream, which
  are compressed and written to an underlying encryption stream."
  ^OutputStream
  [^OutputStream output algorithm]
  (.open (PGPCompressedDataGenerator.
           (tags/compression-algorithm-code algorithm))
         output))


;; Decompress the data contained in the packet.
(extend-protocol MessagePacket
  PGPCompressedData

  (reduce-message
    [packet acc r-fn opts]
    (let [zip-algo (tags/compression-algorithm-tag
                     (.getAlgorithm packet))]
      (reduce
        (fn [acc packet]
          (reduce-message packet acc #(r-fn %1 (assoc %2 :compress zip-algo)) opts))
        acc
        (pgp/read-objects (.getDataStream packet)))))

  (can-decrypt?
    [packet opts]
    true))



;; ## Encrypted Data Packets

(defn- add-encryption-method!
  "Adds an encryption method to an encrypted data generator. Returns the updated
  generator."
  ^PGPEncryptedDataGenerator
  [^PGPEncryptedDataGenerator generator encryptor]
  (cond
    (string? encryptor)
    (.addMethod generator
      (BcPBEKeyEncryptionMethodGenerator.
        (.toCharArray ^String encryptor)))

    (pgp/public-key encryptor)
    (.addMethod generator
      (BcPublicKeyKeyEncryptionMethodGenerator.
        (pgp/public-key encryptor)))

    :else
    (throw (IllegalArgumentException.
             (str "Don't know how to encrypt data with " (pr-str encryptor)))))
  generator)


(defn encrypted-data-stream
  "Wraps an `OutputStream` with an encrypted data generator, returning another
  stream. The data written to the stream will be encrypted with a symmetric
  session key, which is then encrypted for each of the given public keys.

  Typically, the data written to this will consist of compressed data packets.
  If the data is longer than the buffer size, the packet is written in chunks
  in a streaming fashion.

  Options may be provided to customize the packet:

  - `:buffer-size`      maximum number of bytes per chunk
  - `:integrity-packet` whether to include a Modification Detection Code packet
  - `:random`           custom random number generator"
  ^OutputStream
  [^OutputStream output cipher encryptors & opts]
  (let [encryptors (arg-coll encryptors)
        {:keys [buffer-size integrity-packet random]
         :or {buffer-size 4096
              integrity-packet true}}
        (arg-map opts)]
    (when (empty? (remove nil? encryptors))
      (throw (IllegalArgumentException.
               "Cannot encrypt data stream without encryptors.")))
    (when (< 1 (count (filter string? encryptors)))
      (throw (IllegalArgumentException.
               "Only one passphrase encryptor is supported")))
    (.open
      ^PGPEncryptedDataGenerator
      (reduce
        add-encryption-method!
        (PGPEncryptedDataGenerator.
          (cond->
            (BcPGPDataEncryptorBuilder.
              (tags/symmetric-key-algorithm-code cipher))
            integrity-packet (.setWithIntegrityPacket true)
            random           (.setSecureRandom ^SecureRandom random)))
        encryptors)
      output
      (byte-array buffer-size))))


(extend-protocol MessagePacket

  PGPEncryptedDataList

  ;; Read through the list of encrypted session keys and attempt to find one
  ;; which the decryptor will unlock. If none are found, the message is not
  ;; decipherable and an exception is thrown.

  (reduce-message
    [packet acc r-fn opts]
    (when-not (can-decrypt? packet opts)
      (throw (IllegalArgumentException.
               (str "Cannot decrypt " (pr-str packet) " with " (pr-str opts)
                    " (no matching encrypted session key)"))))
    (reduce-message
      (->> (.getEncryptedDataObjects packet)
           iterator-seq
           (filter #(can-decrypt? % opts))
           first)
      acc r-fn opts))

  (can-decrypt?
    [packet opts]
    (boolean
      (some
        #(can-decrypt? % opts)
        (iterator-seq (.getEncryptedDataObjects packet)))))


  PGPPBEEncryptedData

  (reduce-message
    [packet acc r-fn {:keys [decryptor] :as opts}]
    (let [decryptor-factory (BcPBEDataDecryptorFactory.
                              (.toCharArray ^String decryptor)
                              (BcPGPDigestCalculatorProvider.))
          cipher (-> packet
                     (.getSymmetricAlgorithm decryptor-factory)
                     tags/symmetric-key-algorithm-tag)]
      (reduce-conent
        (.getDataStream packet decryptor-factory)
        acc
        #(r-fn %1 (assoc %2 :cipher cipher :object packet))
        opts)))

  ;; If the decryptor is a string, try to use it to decrypt the passphrase
  ;; protected session key.
  (can-decrypt?
    [packet {:keys [decryptor] :as opts}]
    (string? decryptor))


  PGPPublicKeyEncryptedData

  (reduce-message
    [packet acc r-fn {:keys [decryptor] :as opts}]
    (let [for-key (.getKeyID packet)
          privkey (pgp/private-key
                    (if (ifn? decryptor)
                      (decryptor for-key)
                      decryptor))
          decryptor-factory (BcPublicKeyDataDecryptorFactory. privkey)
          cipher (-> packet
                     (.getSymmetricAlgorithm decryptor-factory)
                     tags/symmetric-key-algorithm-tag)]
      (reduce-conent
        (.getDataStream packet decryptor-factory)
        acc
        #(r-fn %1 (assoc %2 :encrypted-for for-key :cipher cipher :object packet))
        opts)))

  ;; If the decryptor is callable, use it to find a private key matching the id
  ;; on the data packet. Otherwise, use it directly as a private key. If the
  ;; decryptor doesn't match the id, return nil.
  (can-decrypt?
    [packet {:keys [decryptor] :as opts}]
    (let [for-key (.getKeyID packet)]
      (if-let [privkey (pgp/private-key
                         (if (ifn? decryptor)
                           (decryptor for-key)
                           decryptor))]
        (= for-key (pgp/key-id privkey))
        false))))



;; ## Constructing PGP Messages

(defn message-output-stream
  "Wraps the given output stream with compression and encryption layers. The
  data will decryptable by the corresponding decryptors. Does _not_ close the
  wrapped stream when it is closed.

  Opts may contain:

  - `:buffer-size` maximum number of bytes per chunk
  - `:compress`    compress the cleartext with the given algorithm, if specified
  - `:cipher`      symmetric key algorithm to use if encryptors are provided
  - `:encryptors`  keys to encrypt the cipher session key with
  - `:armor`       whether to ascii-encode the output

  See `literal-data-stream` and `encrypted-data-stream` for more options."
  ^OutputStream
  [^OutputStream output & opts]
  (let [{:keys [compress cipher encryptors armor]
         :or {cipher :aes-256}
         :as opts}
        (arg-map opts)

        encryptors (arg-coll encryptors)

        wrap-with
        (fn [streams wrapper & args]
          (conj streams (apply wrapper (last streams) args)))

        streams
        (->
          (vector output)
          (cond->
            armor      (wrap-with armored-data-stream)
            encryptors (wrap-with encrypted-data-stream cipher encryptors opts)
            compress   (wrap-with compressed-data-stream compress))
          (wrap-with literal-data-stream opts)
          rest reverse)]
    (proxy [FilterOutputStream] [(first streams)]
      (close []
        (dorun (map #(.close ^OutputStream %) streams))))))


(defn package
  "Compresses, encrypts, and encodes the given data and returns an encoded
  message packet. If the `:armor` option is set, the result will be an ASCII
  string; otherwise, the function returns a byte array.

  The message will readable by any of the corresponding decryptors.

  See `message-output-stream` for options."
  [data & opts]
  (let [opts (arg-map opts)
        buffer (ByteArrayOutputStream.)]
    (with-open [^OutputStream stream
                (message-output-stream buffer opts)]
      (io/copy data stream))
    (cond-> (.toByteArray buffer)
      (:armor opts) String.)))


(defn encrypt
  "Constructs a message packet enciphered for the given encryptors. See
  `message-output-stream` for options."
  [data encryptors & opts]
  (apply package data
         :encryptors encryptors
         opts))



;; ## Reading PGP Messages

(defn- reduce-objects
  "Reduces over the PGP objects the returns the resulting accumulator.
  Verifys the integrity of each object and throws if its invalid."
  [acc r-fn opts objects]
  (reduce
    (fn reduce-and-verify!
      [acc message]
      (reduce-message
        message
        acc
        (fn [acc {:keys [object] :as message}]
          ;; To be able to verify the integrity we must have consumed the stream itself.
          ;; Make sure to call the reducing function and then verify the message.
          (let [results (r-fn acc message)]
            (when (and (isa? (type object) PGPEncryptedData)
                       (.isIntegrityProtected object)
                       (not (.verify object)))
              (throw (IllegalStateException.
                       (str "Encrypted data object " object
                            " failed integrity verification!"))))
            results))
        opts))
    acc
    objects))


(defn reduce-messages
  "Reads message packets form an input source and reduces over them with the
  given accumulator `acc` and reducing function `r-fn`. Each message contains
  keys similiar to the optios used to build them, describing the type of compression used,
  cophier encrypted with, etc. The `r-fn` should take the accumulator and a `message` and
  return the resulting accumulator. It must consume the stream passed in the `:data` field.
  A message is a map containing:
  - `:format` one of #{:binary :text :utf8}
  - `:data` An InputStream
  - `:filename` the name of the file
  - `:mtime` the modified time of the message

  Opts may contain:

  - `:decryptor` secret to decipher the message encryption"
  [input acc r-fn & opts]
  (->> input
       bytes/to-input-stream
       PGPUtil/getDecoderStream
       pgp/read-objects
       (reduce-objects acc r-fn opts)))


(defn read-messages
  "Reads message packets from an input source and returns a sequence of message
  maps.

  See `reduce-messages` for options
  "
  [input & opts]
  (apply
    reduce-messages
    input
    []
    (fn [acc {:keys [format data] :as message}]
      (let [data' (bytes/to-byte-array data)]
        (->> (case format
               (:text :utf8) (String. data')
               data')
             (assoc message :data)
             (conj acc))))
    opts))


(defn decrypt
  "Decrypts a message packet and attempts to decipher it with the given
  decryptor. Returns the data of the first message directly.

  See `read-messages` for options."
  [input decryptor & opts]
  (->
    (apply read-messages input
           :decryptor decryptor
           opts)
    first :data))
