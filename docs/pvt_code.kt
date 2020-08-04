interface CallTree {
    val methodCall: MethodCall
    val callCount: Long
    val wallTime: Long
    val maxWalTime: Long
    val children: Map<MethodCall, CallTree>
}

class MethodCall(
    val tracepoint: Tracepoint,
    val args: String?
)

class Tracepoint(val displayName: String, ...)
