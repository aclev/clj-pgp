(ns mvxcvi.crypto.pgp.test.encryption
  (:require
    [byte-streams :refer [bytes=]]
    [clojure.java.io :as io]
    [clojure.test :refer :all]
    [clojure.test.check :as check]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]
    [mvxcvi.crypto.pgp :as pgp]
    [mvxcvi.crypto.pgp.tags :as tags]
    [mvxcvi.crypto.pgp.test.keys :refer
     [memospec->keypair gen-rsa-keyspec]])
  (:import
    java.io.ByteArrayOutputStream
    java.security.SecureRandom))


(defn test-encryption-scenario
  "Tests that encrypting and decrypting data with the given keypairs/passphrases
  returns the original data."
  [keyspecs data compress cipher armor]
  (testing (str "Encrypting " (count data) " bytes with " cipher
                " for keys " (pr-str keyspecs)
                (when compress (str " compressed with " compress))
                " encoded in " (if armor "ascii" "binary"))
    (let [encryptors (map memospec->keypair keyspecs)
          ciphertext (pgp/encrypt
                       data encryptors
                       :compress compress
                       :cipher cipher
                       :armor armor)]
      (is (not (bytes= data ciphertext))
        "ciphertext bytes differ from data")
      (doseq [decryptor encryptors]
        (is (bytes= data (pgp/decrypt ciphertext decryptor))
            "decrypting the ciphertext returns plaintext"))
      [encryptors ciphertext])))


(def gen-encryptors
  (->>
    (gen/tuple
      gen/boolean
      (gen/not-empty gen/string-ascii)
      (gen/vector (gen-rsa-keyspec [1024 2048 4096])))
    (gen/fmap
      (fn [[p pass keypairs]]
        (-> (if p
              (cons pass keypairs)
              keypairs)
            set shuffle)))
    gen/not-empty))


(def data-encryption-property
  (prop/for-all*
    [gen-encryptors
     (gen/not-empty gen/bytes)
     (gen/elements (cons nil (keys tags/compression-algorithms)))
     (gen/elements (remove #{:null :safer :camellia-256} (keys tags/symmetric-key-algorithms)))
     gen/boolean]
    test-encryption-scenario))


(deftest data-encryption
  (let [rsa (pgp/rsa-keypair-generator 1024)
        keypair (pgp/generate-keypair rsa :rsa-general)
        data "My hidden data files"]
    (is (thrown? IllegalArgumentException
          (pgp/encrypt data :not-an-encryptor
                       :integrity-check false
                       :random (SecureRandom.)))
        "Encryption with an invalid encryptor throws an exception")
    (is (thrown? IllegalArgumentException
          (pgp/encrypt data ["bar" "baz"]))
        "Encryption with multiple passphrases throws an exception")
    (let [ciphertext (pgp/encrypt data keypair)]
      (is (bytes= data (pgp/decrypt ciphertext (constantly keypair)))
          "Decrypting with a keypair-retrieval function returns the data.")
      (is (thrown? IllegalArgumentException
            (pgp/decrypt ciphertext "passphrase"))
          "Decrypting without a matching key throws an exception")))
  (test-encryption-scenario
    ["s3cr3t"]
    "The data blobble"
    nil :aes-128 true)
  (test-encryption-scenario
    [[:rsa :rsa-encrypt 1024]]
    "Secret stuff to hide from prying eyes"
    nil :aes-128 false)
  (test-encryption-scenario
    ["frobble bisvarkian"
     [:rsa :rsa-general 1024]
     [:rsa :rsa-general 2048]]
    "Good news, everyone!"
    :bzip2 :aes-256 true))
