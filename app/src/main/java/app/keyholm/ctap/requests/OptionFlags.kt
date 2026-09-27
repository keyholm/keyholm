package app.keyholm.ctap.requests

import app.keyholm.ctap.Ctap2Result
import app.keyholm.ctap.Ctap2Status
import app.keyholm.ctap.then
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType

internal data class OptionFlags(
    val rk: Boolean?,
    val up: Boolean?,
    val uv: Boolean?,
)

private fun CBORObject.flag(key: String): Ctap2Result<Boolean?> {
    val value = GetOrDefault(key, null) ?: return Ctap2Result.Ok(null)
    return if (value.type == CBORType.Boolean) {
        Ctap2Result.Ok(value.AsBoolean())
    } else {
        Ctap2Result.Rejected(Ctap2Status.CBOR_UNEXPECTED_TYPE)
    }
}

internal fun optionFlags(raw: CBORObject?): Ctap2Result<OptionFlags> {
    if (raw == null) return Ctap2Result.Ok(OptionFlags(rk = null, up = null, uv = null))
    if (raw.type != CBORType.Map) return Ctap2Result.Rejected(Ctap2Status.CBOR_UNEXPECTED_TYPE)
    return raw.flag("rk").then { rk ->
        raw.flag("up").then { up ->
            raw.flag("uv").then { uv -> Ctap2Result.Ok(OptionFlags(rk, up, uv)) }
        }
    }
}
