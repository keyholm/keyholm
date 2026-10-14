# Encrypted store

Every store file is encrypted with AES-GCM using a random DEK.
The DEK itself is stored wrapped with AES-GCM under a KEK
derived from a device-bound, Android keystore HMAC key and a random salt.

Currently there is no DEK rotation, salt rotation functionality and no additional password
feature but all are planned.

Together this means that the store files sit on the filesystem encrypted at rest with
said DEK, which itself is protected by a key that never leaves secure
hardware. Note that both the output of the HMAC and the DEK reside in process
memory.

Keyholm does not hold secrets in these stores, only ever metadata.
