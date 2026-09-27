//! Transport for the Proximity Exchange Protocol (PXP).

mod advert;
mod ffi;

pub use advert::create_advert;

use advert::ADVERT_PLAINTEXT_BYTES;

use p256::PublicKey;
use p256::elliptic_curve::sec1::ToEncodedPoint;
use snow::{Builder, TransportState};

pub type Result<T> = core::result::Result<T, Box<dyn core::error::Error + Send + Sync>>;

const NOISE_PARAMS: &str = "Noise_KNpsk0_P256_AESGCM_SHA256";
const PROLOGUE_QR_INITIATED: &[u8] = &[1u8];
const PADDING_GRANULARITY: usize = 32;
const AEAD_TAG_BYTES: usize = 16;
const PSK_BYTES: usize = 32;
pub(crate) const QR_SECRET_BYTES: usize = 16;
const MAX_NOISE_MESSAGE: usize = 65535;

#[derive(Debug)]
pub struct Session {
    handshake_reply: Vec<u8>,
    transport: TransportState,
}

impl Session {
    /// # Errors
    /// Fails if the handshake message is malformed or does not authenticate.
    pub fn respond(
        secret: &[u8; QR_SECRET_BYTES],
        advert_plaintext: &[u8; ADVERT_PLAINTEXT_BYTES],
        client_public_key: &PublicKey,
        handshake: &[u8],
    ) -> Result<Self> {
        let psk = advert::derive_key::<PSK_BYTES>(
            secret,
            Some(advert_plaintext.as_slice()),
            advert::Purpose::Psk,
        )?;
        let peer = client_public_key.to_encoded_point(false);
        let mut noise = Builder::new(NOISE_PARAMS.parse()?)
            .prologue(PROLOGUE_QR_INITIATED)?
            .remote_public_key(peer.as_bytes())?
            .psk(0, &psk)?
            .build_responder()?;

        noise.read_message(handshake, &mut vec![0u8; handshake.len()])?;

        let mut handshake_reply = vec![0u8; MAX_NOISE_MESSAGE];
        let len = noise.write_message(&[], &mut handshake_reply)?;
        handshake_reply.truncate(len);

        Ok(Self {
            handshake_reply,
            transport: noise.into_transport_mode()?,
        })
    }

    #[must_use]
    pub fn handshake_reply(&self) -> &[u8] {
        &self.handshake_reply
    }

    /// # Errors
    /// Fails if the padded message exceeds the Noise message size limit.
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<Vec<u8>> {
        let padded = pad(plaintext)?;
        let tagged_len = padded
            .len()
            .checked_add(AEAD_TAG_BYTES)
            .ok_or("frame too large")?;
        let mut frame = vec![0u8; tagged_len];
        let len = self.transport.write_message(&padded, &mut frame)?;
        frame.truncate(len);
        Ok(frame)
    }

    /// # Errors
    /// Fails if the frame does not authenticate or its padding is invalid.
    pub fn decrypt(&mut self, frame: &[u8]) -> Result<Vec<u8>> {
        let mut plaintext = vec![0u8; frame.len()];
        let len = self.transport.read_message(frame, &mut plaintext)?;
        plaintext.truncate(len);
        unpad(plaintext)
    }
}

/// Grows the message to a multiple of 32 bytes; the last byte holds the number
/// of bytes added, minus one. At least one byte is always added.
fn pad(plaintext: &[u8]) -> Result<Vec<u8>> {
    let zeros = (PADDING_GRANULARITY - 1)
        .checked_sub(plaintext.len() % PADDING_GRANULARITY)
        .ok_or("padding underflow")?;
    let mut padded = plaintext.to_vec();
    padded.extend(core::iter::repeat_n(0u8, zeros));
    padded.push(u8::try_from(zeros)?);
    Ok(padded)
}

fn unpad(mut padded: Vec<u8>) -> Result<Vec<u8>> {
    let added = *padded.last().ok_or("empty frame")? as usize + 1;
    let len = padded
        .len()
        .checked_sub(added)
        .ok_or("padding exceeds frame")?;
    padded.truncate(len);
    Ok(padded)
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::SecretKey;
    use snow::HandshakeState;
    use std::assert_matches;

    const CLIENT_SCALAR: [u8; 32] = [0x11; 32];
    const QR_SECRET: [u8; QR_SECRET_BYTES] = [0x22; QR_SECRET_BYTES];
    const ADVERT_PLAINTEXT: [u8; ADVERT_PLAINTEXT_BYTES] = [0x33; ADVERT_PLAINTEXT_BYTES];

    fn test_psk() -> [u8; PSK_BYTES] {
        advert::derive_key::<PSK_BYTES>(
            &QR_SECRET,
            Some(&ADVERT_PLAINTEXT),
            advert::Purpose::Psk,
        )
        .expect("PSK_BYTES is within the HKDF-SHA256 output limit")
    }

    fn libwebauthn_initiator(private_key: &[u8], psk: &[u8; PSK_BYTES]) -> HandshakeState {
        Builder::new(
            NOISE_PARAMS
                .parse()
                .expect("snow supports NOISE_PARAMS with use-p256"),
        )
        .prologue(PROLOGUE_QR_INITIATED)
        .expect("the builder is fresh, so the prologue is set once")
        .local_private_key(private_key)
        .expect("the builder is fresh, so the private key is set once")
        .psk(0, psk)
        .expect("KNpsk0 has a psk modifier at location 0")
        .build_initiator()
        .expect("KN needs the initiator static key, which is set")
    }

    #[test]
    fn pads_to_a_multiple_of_32_and_round_trips() {
        for len in [0usize, 1, 31, 32, 33, 64, 100] {
            let plaintext = vec![0xABu8; len];
            let padded =
                pad(&plaintext).expect("at most 31 zero bytes are added, so the count fits a byte");
            assert_eq!(padded.len() % PADDING_GRANULARITY, 0);
            assert!(padded.len() > len);
            assert_eq!(
                unpad(padded).expect("pad always ends with a valid count byte"),
                plaintext
            );
        }
    }

    #[test]
    fn unpad_rejects_a_frame_shorter_than_its_declared_padding() {
        assert_matches!(unpad(Vec::new()), Err(_));
        assert_matches!(unpad(vec![0x05]), Err(_));
    }

    #[test]
    fn handshake_answers_a_libwebauthn_initiator() {
        let secret = SecretKey::from_slice(&CLIENT_SCALAR)
            .expect("CLIENT_SCALAR is nonzero and below the P-256 order");
        let private_key = secret.to_bytes();
        let mut initiator = libwebauthn_initiator(&private_key, &test_psk());

        let mut first = vec![0u8; 1024];
        let len = initiator
            .write_message(&[], &mut first)
            .expect("the first KNpsk0 message fits in 1024 bytes");
        first.truncate(len);
        assert_eq!(
            first.len(),
            81,
            "ephemeral P-256 point plus an empty AEAD payload"
        );

        let mut session =
            Session::respond(&QR_SECRET, &ADVERT_PLAINTEXT, &secret.public_key(), &first)
                .expect("the initiator used the same QR secret, advert plaintext and client key");
        assert_eq!(session.handshake_reply().len(), 81);

        initiator
            .read_message(session.handshake_reply(), &mut [0u8; 1024])
            .expect("the responder derived the same PSK and the reply fits in 1024 bytes");
        assert!(initiator.is_handshake_finished());
        let mut initiator = initiator
            .into_transport_mode()
            .expect("the handshake is finished, as asserted above");

        let post_handshake = b"\xa1\x01\x43\x01\x02\x03";
        let frame = session
            .encrypt(post_handshake)
            .expect("6 bytes pads to 32, far below the Noise message limit");
        let mut out = vec![0u8; 1024];
        let len = initiator
            .read_message(&frame, &mut out)
            .expect("both sides split the same handshake into matching keys");
        out.truncate(len);
        assert_eq!(
            unpad(out).expect("Session::encrypt pads before encrypting"),
            post_handshake
        );

        let request =
            pad(b"\x01\x04").expect("at most 31 zero bytes are added, so the count fits a byte");
        let mut frame = vec![0u8; 1024];
        let len = initiator
            .write_message(&request, &mut frame)
            .expect("a 32-byte request fits in 1024 bytes");
        frame.truncate(len);
        assert_eq!(
            session
                .decrypt(&frame)
                .expect("both sides split the same handshake into matching keys"),
            b"\x01\x04"
        );
    }

    #[test]
    fn handshake_rejects_the_wrong_psk() {
        let secret = SecretKey::from_slice(&CLIENT_SCALAR)
            .expect("CLIENT_SCALAR is nonzero and below the P-256 order");
        let private_key = secret.to_bytes();
        let mut initiator = libwebauthn_initiator(&private_key, &test_psk());

        let mut first = vec![0u8; 1024];
        let len = initiator
            .write_message(&[], &mut first)
            .expect("the first KNpsk0 message fits in 1024 bytes");
        first.truncate(len);

        let wrong_secret = [0u8; 16];
        assert_matches!(
            Session::respond(
                &wrong_secret,
                &ADVERT_PLAINTEXT,
                &secret.public_key(),
                &first
            ),
            Err(_)
        );
    }
}
