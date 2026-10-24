package com.vlad.mcpserver;

import io.ktor.server.websocket.DefaultWebSocketServerSession;
import kotlin.Metadata;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.jvm.internal.DebugMetadata;
import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function2;

/* JADX INFO: compiled from: McpServerEngine.android.kt */
/* JADX INFO: loaded from: /private/tmp/overlay-classes2.dex */
@Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0010\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u00020\u0001*\u00020\u0002H\n"}, d2 = {"<anonymous>", "", "Lio/ktor/server/websocket/DefaultWebSocketServerSession;"}, k = 3, mv = {2, 4, 0}, xi = 1328)
@DebugMetadata(c = "com.vlad.mcpserver.McpServerEngine$configureServerModule$1$6", f = "McpServerEngine.android.kt", i = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1}, l = {105, 109}, m = "invokeSuspend", n = {"$this$webSocket", "authHeader", "clientId", "clientName", "configuredToken", "isAuthenticated", "$this$webSocket", "authHeader", "clientId", "clientName", "configuredToken", "frame", "text", "response", "isAuthenticated"}, nl = {106, 115}, s = {"L$0", "L$1", "L$2", "L$3", "L$4", "I$0", "L$0", "L$1", "L$2", "L$3", "L$4", "L$6", "L$7", "L$8", "I$0"}, v = 2)
final class McpServerEngine$configureServerModule$1$6 extends SuspendLambda implements Function2<DefaultWebSocketServerSession, Continuation<? super Unit>, Object> {
    int I$0;
    private /* synthetic */ Object L$0;
    Object L$1;
    Object L$2;
    Object L$3;
    Object L$4;
    Object L$5;
    Object L$6;
    Object L$7;
    Object L$8;
    int label;
    final /* synthetic */ McpServerEngine this$0;

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    McpServerEngine$configureServerModule$1$6(McpServerEngine mcpServerEngine, Continuation<? super McpServerEngine$configureServerModule$1$6> continuation) {
        super(2, continuation);
        this.this$0 = mcpServerEngine;
    }

    public final Continuation<Unit> create(Object obj, Continuation<?> continuation) {
        Continuation<Unit> mcpServerEngine$configureServerModule$1$6 = new McpServerEngine$configureServerModule$1$6(this.this$0, continuation);
        mcpServerEngine$configureServerModule$1$6.L$0 = obj;
        return mcpServerEngine$configureServerModule$1$6;
    }

    public final Object invoke(DefaultWebSocketServerSession defaultWebSocketServerSession, Continuation<? super Unit> continuation) {
        return create(defaultWebSocketServerSession, continuation).invokeSuspend(Unit.INSTANCE);
    }

    /* JADX WARN: Code duplicated, block: B:34:0x011e A[RETURN] */
    /* JADX WARN: Code duplicated, block: B:37:0x0127 A[Catch: all -> 0x01a7, Exception -> 0x01ab, TryCatch #7 {Exception -> 0x01ab, all -> 0x01a7, blocks: (B:35:0x011f, B:37:0x0127, B:39:0x0131), top: B:69:0x011f }] */
    /* JADX WARN: Code duplicated, block: B:39:0x0131 A[Catch: all -> 0x01a7, Exception -> 0x01ab, TRY_LEAVE, TryCatch #7 {Exception -> 0x01ab, all -> 0x01a7, blocks: (B:35:0x011f, B:37:0x0127, B:39:0x0131), top: B:69:0x011f }] */
    /* JADX WARN: Code duplicated, block: B:43:0x0181 A[RETURN] */
    /* JADX WARN: Code duplicated, block: B:44:0x0182  */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:44:0x0182 -> B:45:0x0189). Please report as a decompilation issue!!! */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:50:0x0196 -> B:67:0x00f2). Please report as a decompilation issue!!! */
    /*  JADX ERROR: JadxOverflowException in pass: RegionMakerVisitor
        jadx.core.utils.exceptions.JadxOverflowException: Regions stack size limit reached
        	at jadx.core.utils.ErrorsCounter.addError(ErrorsCounter.java:59)
        	at jadx.core.utils.ErrorsCounter.error(ErrorsCounter.java:31)
        	at jadx.core.dex.attributes.nodes.NotificationAttrNode.addError(NotificationAttrNode.java:19)
        */
    public final java.lang.Object invokeSuspend(java.lang.Object r17) {
        /*
            Method dump skipped, instruction units count: 470
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.vlad.mcpserver.McpServerEngine$configureServerModule$1$6.invokeSuspend(java.lang.Object):java.lang.Object");
    }
}
