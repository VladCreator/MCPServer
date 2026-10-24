package com.vlad.mcpserver;

import io.ktor.server.routing.RoutingContext;
import kotlin.Metadata;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.coroutines.jvm.internal.DebugMetadata;
import kotlin.coroutines.jvm.internal.SpillingKt;
import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function2;

/* JADX INFO: compiled from: McpServerEngine.android.kt */
/* JADX INFO: loaded from: /private/tmp/overlay-classes2.dex */
@Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0010\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u00020\u0001*\u00020\u0002H\n"}, d2 = {"<anonymous>", "", "Lio/ktor/server/routing/RoutingContext;"}, k = 3, mv = {2, 4, 0}, xi = 1328)
@DebugMetadata(c = "com.vlad.mcpserver.McpServerEngine$configureServerModule$1$3", f = "McpServerEngine.android.kt", i = {0}, l = {78}, m = "invokeSuspend", n = {"$this$get"}, nl = {-1}, s = {"L$0"}, v = 2)
final class McpServerEngine$configureServerModule$1$3 extends SuspendLambda implements Function2<RoutingContext, Continuation<? super Unit>, Object> {
    final /* synthetic */ int $targetPort;
    private /* synthetic */ Object L$0;
    int label;
    final /* synthetic */ McpServerEngine this$0;

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    McpServerEngine$configureServerModule$1$3(McpServerEngine mcpServerEngine, int i, Continuation<? super McpServerEngine$configureServerModule$1$3> continuation) {
        super(2, continuation);
        this.this$0 = mcpServerEngine;
        this.$targetPort = i;
    }

    public final Continuation<Unit> create(Object obj, Continuation<?> continuation) {
        Continuation<Unit> mcpServerEngine$configureServerModule$1$3 = new McpServerEngine$configureServerModule$1$3(this.this$0, this.$targetPort, continuation);
        mcpServerEngine$configureServerModule$1$3.L$0 = obj;
        return mcpServerEngine$configureServerModule$1$3;
    }

    public final Object invoke(RoutingContext routingContext, Continuation<? super Unit> continuation) {
        return create(routingContext, continuation).invokeSuspend(Unit.INSTANCE);
    }

    public final Object invokeSuspend(Object $result) {
        RoutingContext $this$get = (RoutingContext) this.L$0;
        Object coroutine_suspended = IntrinsicsKt.getCOROUTINE_SUSPENDED();
        switch (this.label) {
            case 0:
                ResultKt.throwOnFailure($result);
                this.L$0 = SpillingKt.nullOutSpilledVariable($this$get);
                this.label = 1;
                if (this.this$0.handleSseConnection($this$get, this.$targetPort, (Continuation) this) == coroutine_suspended) {
                    return coroutine_suspended;
                }
                break;
            case 1:
                ResultKt.throwOnFailure($result);
                break;
            default:
                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
        }
        return Unit.INSTANCE;
    }
}
