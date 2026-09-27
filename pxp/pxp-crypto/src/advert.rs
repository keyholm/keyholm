//! PXP key derivation (HKDF-SHA256) and the BLE advertisement's AES + HMAC tag.

use aes::Aes256;
use aes::cipher::{BlockEncrypt, KeyInit};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use sha2::Sha256;
use zeroize::Zeroizing;

use crate::{QR_SECRET_BYTES, Result};

/// HKDF `info`
#[derive(Clone, Copy)]
pub(crate) enum Purpose {
    EidKey = 1,
    Psk = 3,
}

const EID_KEY_BYTES: usize = 64;
const AES_KEY_BYTES: usize = 32;
pub(crate) const ADVERT_PLAINTEXT_BYTES: usize = 16;
const ADVERT_TAG_BYTES: usize = 4;

/// HKDF-SHA256 (RFC 5869)
pub(crate) fn derive_key<const N: usize>(
    secret: &[u8],
    salt: Option<&[u8]>,
    purpose: Purpose,
) -> Result<Zeroizing<[u8; N]>> {
    let hkdf = Hkdf::<Sha256>::new(salt, secret);
    let mut out = Zeroizing::new([0u8; N]);
    // hkdf::InvalidLength doesn't implement Error
    hkdf.expand(&(purpose as u32).to_le_bytes(), out.as_mut_slice())
        .map_err(|e| e.to_string())?;
    Ok(out)
}

fn encrypt_advert(eid_key: &[u8], plaintext: &[u8; ADVERT_PLAINTEXT_BYTES]) -> Result<Vec<u8>> {
    let (aes_key, hmac_key) = eid_key.split_at(AES_KEY_BYTES);
    let mut key = Zeroizing::new([0u8; AES_KEY_BYTES]);
    key.copy_from_slice(aes_key);
    let cipher = Aes256::new(key.as_slice().into());
    let mut block = *plaintext;
    cipher.encrypt_block((&mut block).into());

    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(hmac_key)?;
    mac.update(&block);
    let tag = mac.finalize().into_bytes();

    let mut advert = block.to_vec();
    advert.extend_from_slice(&tag[..ADVERT_TAG_BYTES]);
    Ok(advert)
}

/// # Errors
/// Fails only if key derivation or HMAC setup rejects a key length.
pub fn create_advert(
    secret: &[u8; QR_SECRET_BYTES],
    plaintext: &[u8; ADVERT_PLAINTEXT_BYTES],
) -> Result<Vec<u8>> {
    let eid_key = derive_key::<EID_KEY_BYTES>(secret, None, Purpose::EidKey)?;
    encrypt_advert(eid_key.as_slice(), plaintext)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(text: &str) -> Vec<u8> {
        text.as_bytes()
            .chunks(2)
            .map(|pair| {
                let pair = core::str::from_utf8(pair).expect("test vectors are ASCII");
                u8::from_str_radix(pair, 16).expect("test vectors contain only hex digits")
            })
            .collect()
    }

    const QR_SECRET: &str = "00112233445566778899aabbccddeeff";
    const EID_KEY_NO_SALT: &str = "efafab5b2c84a11c80e3ad0770353138b414a859ccd3afcc99e3d3250dba650\
        84ede8e38e75432617c0ccae1ffe5d8143df0db0cd6d296f489419cd6411ee505";
    const EID_KEY_WITH_SALT: &str = "168cf3dd220a7907f8bac30f559be92a3b6d937fe5594beeaf1e50e35976b7d\
        654dd550e22ae4c801b9d1cdbf0d2b1472daa1328661eb889acae3023b7ffa509";

    #[test]
    fn derive_key_matches_the_libwebauthn_vector_without_a_salt() {
        let derived = derive_key::<64>(&hex(QR_SECRET), None, Purpose::EidKey)
            .expect("64 bytes is within the HKDF-SHA256 output limit");
        assert_eq!(derived.as_slice(), hex(EID_KEY_NO_SALT));
    }

    #[test]
    fn derive_key_matches_the_libwebauthn_vector_with_a_salt() {
        let salt = hex("ffeeddccbbaa998877665544332211");
        let derived = derive_key::<64>(&hex(QR_SECRET), Some(&salt), Purpose::EidKey)
            .expect("64 bytes is within the HKDF-SHA256 output limit");
        assert_eq!(derived.as_slice(), hex(EID_KEY_WITH_SALT));
    }

    #[test]
    fn derive_key_returns_the_requested_length() {
        let derived = derive_key::<32>(&hex(QR_SECRET), None, Purpose::EidKey)
            .expect("32 bytes is within the HKDF-SHA256 output limit");
        assert_eq!(derived.as_slice(), &hex(EID_KEY_NO_SALT)[..32]);
    }

    #[test]
    fn encrypt_advert_produces_sixteen_ciphertext_bytes_and_a_four_byte_tag() {
        let eid_key: Vec<u8> = (0..64u8).collect();
        let plaintext = hex("000102030405060708090aaabbcc0000")
            .try_into()
            .expect("the vector is 32 hex digits");

        let advert = encrypt_advert(&eid_key, &plaintext)
            .expect("a 64-byte EID key holds the 32-byte AES key and the HMAC key");

        assert_eq!(advert, hex("245d6f09c29b3275935d3b9eeddbff041d656290"));
    }

    #[test]
    fn create_advert_matches_the_end_to_end_vector() {
        // flags(1) || nonce(10) || zeros(5)
        let nonce = hex("0102030405060708090a");
        let mut plaintext = [0u8; ADVERT_PLAINTEXT_BYTES];
        plaintext[1..11].copy_from_slice(&nonce);

        let secret = hex(QR_SECRET)
            .try_into()
            .expect("QR_SECRET is 32 hex digits");
        let advert = create_advert(&secret, &plaintext)
            .expect("key lengths are fixed and within HKDF and HMAC limits");

        assert_eq!(advert, hex("93f625c64f5c99f5534dced8e19638c40fd88ba5"));
    }
}
