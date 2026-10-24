package com.vlad.mcpserver;

import io.ktor.http.BadContentTypeFormatException;
import io.ktor.http.ContentType;
import io.ktor.http.HttpHeaders;
import io.ktor.http.HttpStatusCode;
import io.ktor.server.application.ApplicationCall;
import io.ktor.server.plugins.BadRequestException;
import io.ktor.server.plugins.CannotTransformContentToTypeException;
import io.ktor.server.request.ApplicationRequestPropertiesKt;
import io.ktor.server.response.ApplicationResponseFunctionsKt;
import io.ktor.server.routing.RoutingContext;
import io.ktor.util.reflect.TypeInfo;
import io.ktor.utils.io.ByteReadChannel;
import io.ktor.utils.io.ByteReadChannelOperationsKt;
import io.ktor.utils.io.core.StringsKt;
import java.nio.charset.Charset;
import kotlin.Metadata;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.coroutines.jvm.internal.DebugMetadata;
import kotlin.coroutines.jvm.internal.SpillingKt;
import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.Reflection;
import kotlin.reflect.KClass;
import kotlin.reflect.KType;
import kotlin.text.Charsets;
import kotlinx.io.Source;

/* JADX INFO: compiled from: McpServerEngine.android.kt */
/* JADX INFO: loaded from: /private/tmp/overlay-classes2.dex */
@Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0010\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u00020\u0001*\u00020\u0002H\n"}, d2 = {"<anonymous>", "", "Lio/ktor/server/routing/RoutingContext;"}, k = 3, mv = {2, 4, 0}, xi = 1328)
@DebugMetadata(c = "com.vlad.mcpserver.McpServerEngine$configureServerModule$1$5", f = "McpServerEngine.android.kt", i = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2}, l = {302, 300, 86}, m = "invokeSuspend", n = {"$this$post", "sessionId", "$this$receiveText$iv", "$this$receiveChannel$iv$iv", "charset$iv", "$this$receive$iv$iv$iv", "$this$post", "sessionId", "$this$receiveText$iv", "charset$iv", "$this$post", "sessionId", "requestBody", "responseJson"}, nl = {321, 84, 87}, s = {"L$0", "L$1", "L$2", "L$3", "L$4", "L$5", "L$0", "L$1", "L$2", "L$3", "L$0", "L$1", "L$2", "L$3"}, v = 2)
final class McpServerEngine$configureServerModule$1$5 extends SuspendLambda implements Function2<RoutingContext, Continuation<? super Unit>, Object> {
    private /* synthetic */ Object L$0;
    Object L$1;
    Object L$2;
    Object L$3;
    Object L$4;
    Object L$5;
    int label;
    final /* synthetic */ McpServerEngine this$0;

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    McpServerEngine$configureServerModule$1$5(McpServerEngine mcpServerEngine, Continuation<? super McpServerEngine$configureServerModule$1$5> continuation) {
        super(2, continuation);
        this.this$0 = mcpServerEngine;
    }

    public final Continuation<Unit> create(Object obj, Continuation<?> continuation) {
        Continuation<Unit> mcpServerEngine$configureServerModule$1$5 = new McpServerEngine$configureServerModule$1$5(this.this$0, continuation);
        mcpServerEngine$configureServerModule$1$5.L$0 = obj;
        return mcpServerEngine$configureServerModule$1$5;
    }

    public final Object invoke(RoutingContext routingContext, Continuation<? super Unit> continuation) {
        return create(routingContext, continuation).invokeSuspend(Unit.INSTANCE);
    }

    /* JADX INFO: Thrown type has an unknown type hierarchy: io.ktor.server.plugins.BadRequestException */
    /* JADX INFO: Thrown type has an unknown type hierarchy: io.ktor.server.plugins.CannotTransformContentToTypeException */
    /* JADX WARN: Code duplicated, block: B:26:0x00dd  */
    /* JADX WARN: Code duplicated, block: B:28:0x00fb A[RETURN] */
    /* JADX WARN: Code duplicated, block: B:29:0x00fc  */
    /* JADX WARN: Code duplicated, block: B:32:0x0141 A[RETURN] */
    /* JADX WARN: Code duplicated, block: B:33:0x0142  */
    /* JADX WARN: Code duplicated, block: B:36:0x0147  */
    public final Object invokeSuspend(Object $result) throws CannotTransformContentToTypeException, BadRequestException {
        String sessionId;
        Charset charset$iv;
        KType kTypeTypeOf;
        Object objReceiveNullable;
        ApplicationCall $this$receive$iv$iv$iv;
        int i;
        Object remaining;
        String sessionId2;
        String responseJson;
        RoutingContext $this$post = (RoutingContext) this.L$0;
        Object coroutine_suspended = IntrinsicsKt.getCOROUTINE_SUSPENDED();
        KType kTypeTypeOf2 = null;
        switch (this.label) {
            case 0:
                ResultKt.throwOnFailure($result);
                String str = $this$post.getCall().getRequest().getQueryParameters().get("sessionId");
                if (str == null) {
                    str = "sse-copilot-agent";
                }
                sessionId = str;
                ApplicationCall $this$receiveText$iv = $this$post.getCall();
                try {
                    Charset charsetContentCharset = ApplicationRequestPropertiesKt.contentCharset($this$receiveText$iv.getRequest());
                    if (charsetContentCharset == null) {
                        charsetContentCharset = Charsets.UTF_8;
                        break;
                    }
                    charset$iv = charsetContentCharset;
                    KClass orCreateKotlinClass = Reflection.getOrCreateKotlinClass(ByteReadChannel.class);
                    try {
                        kTypeTypeOf = Reflection.typeOf(ByteReadChannel.class);
                        break;
                    } catch (Throwable th) {
                        kTypeTypeOf = null;
                    }
                    this.L$0 = $this$post;
                    this.L$1 = sessionId;
                    this.L$2 = SpillingKt.nullOutSpilledVariable($this$receiveText$iv);
                    this.L$3 = SpillingKt.nullOutSpilledVariable($this$receiveText$iv);
                    this.L$4 = charset$iv;
                    this.L$5 = SpillingKt.nullOutSpilledVariable($this$receiveText$iv);
                    this.label = 1;
                    objReceiveNullable = $this$receiveText$iv.receiveNullable(new TypeInfo(orCreateKotlinClass, kTypeTypeOf), (Continuation) this);
                    if (objReceiveNullable == coroutine_suspended) {
                        return coroutine_suspended;
                    }
                    $this$receive$iv$iv$iv = $this$receiveText$iv;
                    if (objReceiveNullable != null) {
                        KClass orCreateKotlinClass2 = Reflection.getOrCreateKotlinClass(ByteReadChannel.class);
                        try {
                            kTypeTypeOf2 = Reflection.typeOf(ByteReadChannel.class);
                            break;
                        } catch (Throwable th2) {
                        }
                        KType kotlinType = new TypeInfo(orCreateKotlinClass2, kTypeTypeOf2).getKotlinType();
                        Intrinsics.checkNotNull(kotlinType);
                        throw new CannotTransformContentToTypeException(kotlinType);
                    }
                    this.L$0 = $this$post;
                    this.L$1 = sessionId;
                    this.L$2 = SpillingKt.nullOutSpilledVariable($this$receive$iv$iv$iv);
                    this.L$3 = charset$iv;
                    this.L$4 = null;
                    this.L$5 = null;
                    i = 2;
                    this.label = 2;
                    remaining = ByteReadChannelOperationsKt.readRemaining((ByteReadChannel) objReceiveNullable, (Continuation) this);
                    if (remaining == coroutine_suspended) {
                        return coroutine_suspended;
                    }
                    sessionId2 = sessionId;
                    String requestBody = StringsKt.readText$default((Source) remaining, charset$iv, 0, i, (Object) null);
                    responseJson = this.this$0.processMcpJsonRpcRequest(sessionId2, requestBody);
                    this.L$0 = SpillingKt.nullOutSpilledVariable($this$post);
                    this.L$1 = SpillingKt.nullOutSpilledVariable(sessionId2);
                    this.L$2 = SpillingKt.nullOutSpilledVariable(requestBody);
                    this.L$3 = SpillingKt.nullOutSpilledVariable(responseJson);
                    this.label = 3;
                    if (ApplicationResponseFunctionsKt.respondText$default($this$post.getCall(), responseJson, ContentType.Application.INSTANCE.getJson(), (HttpStatusCode) null, (Function1) null, (Continuation) this, 12, (Object) null) == coroutine_suspended) {
                        return coroutine_suspended;
                    }
                    return Unit.INSTANCE;
                } catch (BadContentTypeFormatException e) {
                    throw new BadRequestException("Illegal Content-Type format: " + $this$receiveText$iv.getRequest().getHeaders().get(HttpHeaders.INSTANCE.getContentType()), e);
                }
            case 1:
                Charset charset$iv2 = (Charset) this.L$4;
                $this$receive$iv$iv$iv = (ApplicationCall) this.L$2;
                sessionId = (String) this.L$1;
                ResultKt.throwOnFailure($result);
                charset$iv = charset$iv2;
                objReceiveNullable = $result;
                if (objReceiveNullable != null) {
                    KClass orCreateKotlinClass3 = Reflection.getOrCreateKotlinClass(ByteReadChannel.class);
                    kTypeTypeOf2 = Reflection.typeOf(ByteReadChannel.class);
                    KType kotlinType2 = new TypeInfo(orCreateKotlinClass3, kTypeTypeOf2).getKotlinType();
                    Intrinsics.checkNotNull(kotlinType2);
                    throw new CannotTransformContentToTypeException(kotlinType2);
                }
                this.L$0 = $this$post;
                this.L$1 = sessionId;
                this.L$2 = SpillingKt.nullOutSpilledVariable($this$receive$iv$iv$iv);
                this.L$3 = charset$iv;
                this.L$4 = null;
                this.L$5 = null;
                i = 2;
                this.label = 2;
                remaining = ByteReadChannelOperationsKt.readRemaining((ByteReadChannel) objReceiveNullable, (Continuation) this);
                if (remaining == coroutine_suspended) {
                    return coroutine_suspended;
                }
                sessionId2 = sessionId;
                String requestBody2 = StringsKt.readText$default((Source) remaining, charset$iv, 0, i, (Object) null);
                responseJson = this.this$0.processMcpJsonRpcRequest(sessionId2, requestBody2);
                this.L$0 = SpillingKt.nullOutSpilledVariable($this$post);
                this.L$1 = SpillingKt.nullOutSpilledVariable(sessionId2);
                this.L$2 = SpillingKt.nullOutSpilledVariable(requestBody2);
                this.L$3 = SpillingKt.nullOutSpilledVariable(responseJson);
                this.label = 3;
                if (ApplicationResponseFunctionsKt.respondText$default($this$post.getCall(), responseJson, ContentType.Application.INSTANCE.getJson(), (HttpStatusCode) null, (Function1) null, (Continuation) this, 12, (Object) null) == coroutine_suspended) {
                    return coroutine_suspended;
                }
                return Unit.INSTANCE;
            case 2:
                Charset charset$iv3 = (Charset) this.L$3;
                String sessionId3 = (String) this.L$1;
                ResultKt.throwOnFailure($result);
                sessionId2 = sessionId3;
                charset$iv = charset$iv3;
                i = 2;
                remaining = $result;
                String requestBody3 = StringsKt.readText$default((Source) remaining, charset$iv, 0, i, (Object) null);
                responseJson = this.this$0.processMcpJsonRpcRequest(sessionId2, requestBody3);
                this.L$0 = SpillingKt.nullOutSpilledVariable($this$post);
                this.L$1 = SpillingKt.nullOutSpilledVariable(sessionId2);
                this.L$2 = SpillingKt.nullOutSpilledVariable(requestBody3);
                this.L$3 = SpillingKt.nullOutSpilledVariable(responseJson);
                this.label = 3;
                if (ApplicationResponseFunctionsKt.respondText$default($this$post.getCall(), responseJson, ContentType.Application.INSTANCE.getJson(), (HttpStatusCode) null, (Function1) null, (Continuation) this, 12, (Object) null) == coroutine_suspended) {
                    return coroutine_suspended;
                }
                return Unit.INSTANCE;
            case 3:
                ResultKt.throwOnFailure($result);
                return Unit.INSTANCE;
            default:
                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
        }
    }
}
