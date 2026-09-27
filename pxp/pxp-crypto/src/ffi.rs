//! JNI entry points for `app.keyholm.pxp.crypto.PxpNative`.

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JObject};
use jni::sys::jlong;
use p256::PublicKey;
use zeroize::Zeroizing;

use crate::{Result, Session, create_advert};

/// `ThrowRuntimeExAndDefault` needs a sized error type, which `Box<dyn Error>` is not.
#[derive(Debug)]
struct Thrown(String);

impl core::fmt::Display for Thrown {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(&self.0)
    }
}

impl core::error::Error for Thrown {}

impl From<jni::errors::Error> for Thrown {
    fn from(error: jni::errors::Error) -> Self {
        Self(error.to_string())
    }
}

impl From<core::array::TryFromSliceError> for Thrown {
    fn from(error: core::array::TryFromSliceError) -> Self {
        Self(error.to_string())
    }
}

impl From<p256::elliptic_curve::Error> for Thrown {
    fn from(error: p256::elliptic_curve::Error) -> Self {
        Self(error.to_string())
    }
}

impl From<Box<dyn core::error::Error + Send + Sync>> for Thrown {
    fn from(error: Box<dyn core::error::Error + Send + Sync>) -> Self {
        Self(error.to_string())
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_keyholm_pxp_crypto_PxpNative_open<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    secret: JByteArray<'caller>,
    advert_plaintext: JByteArray<'caller>,
    client_public_key: JByteArray<'caller>,
    handshake: JByteArray<'caller>,
) -> jlong {
    unowned
        .with_env(|env| -> core::result::Result<jlong, Thrown> {
            let secret = Zeroizing::new(env.convert_byte_array(&secret)?);
            let advert_plaintext = env.convert_byte_array(&advert_plaintext)?;
            let client_public_key =
                PublicKey::from_sec1_bytes(&env.convert_byte_array(&client_public_key)?)?;
            let session = Session::respond(
                secret.as_slice().try_into()?,
                advert_plaintext.as_slice().try_into()?,
                &client_public_key,
                &env.convert_byte_array(&handshake)?,
            )?;
            Ok(Box::into_raw(Box::new(session)) as jlong)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_keyholm_pxp_crypto_PxpNative_createAdvert<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    secret: JByteArray<'caller>,
    plaintext: JByteArray<'caller>,
) -> JByteArray<'caller> {
    unowned
        .with_env(|env| -> core::result::Result<JByteArray<'caller>, Thrown> {
            let secret = Zeroizing::new(env.convert_byte_array(&secret)?);
            let plaintext = env.convert_byte_array(&plaintext)?;
            let advert = create_advert(
                secret.as_slice().try_into()?,
                plaintext.as_slice().try_into()?,
            )?;
            Ok(env.byte_array_from_slice(&advert)?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_keyholm_pxp_crypto_PxpNative_handshakeReply<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
) -> JByteArray<'caller> {
    unowned
        .with_env(|env| -> core::result::Result<JByteArray<'caller>, Thrown> {
            Ok(env.byte_array_from_slice(session(handle)?.handshake_reply())?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_keyholm_pxp_crypto_PxpNative_encrypt<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    plaintext: JByteArray<'caller>,
) -> JByteArray<'caller> {
    unowned
        .with_env(|env| -> core::result::Result<JByteArray<'caller>, Thrown> {
            let plaintext = env.convert_byte_array(&plaintext)?;
            let frame = session(handle)?.encrypt(&plaintext)?;
            Ok(env.byte_array_from_slice(&frame)?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_keyholm_pxp_crypto_PxpNative_decrypt<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    frame: JByteArray<'caller>,
) -> JByteArray<'caller> {
    unowned
        .with_env(|env| -> core::result::Result<JByteArray<'caller>, Thrown> {
            let frame = env.convert_byte_array(&frame)?;
            let plaintext = session(handle)?.decrypt(&frame)?;
            Ok(env.byte_array_from_slice(&plaintext)?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_keyholm_pxp_crypto_PxpNative_close(
    _unowned: EnvUnowned,
    _this: JObject,
    handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Session) });
    }
}

fn session<'a>(handle: jlong) -> Result<&'a mut Session> {
    if handle == 0 {
        return Err("session is closed".into());
    }
    Ok(unsafe { &mut *(handle as *mut Session) })
}
