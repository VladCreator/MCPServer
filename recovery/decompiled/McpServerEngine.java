package com.vlad.mcpserver;

import io.ktor.http.ContentType;
import io.ktor.http.HttpHeaders;
import io.ktor.http.HttpStatusCode;
import io.ktor.server.application.Application;
import io.ktor.server.application.ApplicationEnvironment;
import io.ktor.server.application.ApplicationPluginKt;
import io.ktor.server.cio.CIO;
import io.ktor.server.cio.CIOApplicationEngine;
import io.ktor.server.engine.ApplicationEngine;
import io.ktor.server.engine.ConnectorType;
import io.ktor.server.engine.EmbeddedServer;
import io.ktor.server.engine.EmbeddedServerKt;
import io.ktor.server.engine.EngineConnectorBuilder;
import io.ktor.server.engine.EngineSSLConnectorBuilder;
import io.ktor.server.netty.Netty;
import io.ktor.server.netty.NettyApplicationEngine;
import io.ktor.server.response.ApplicationResponseFunctionsJvmKt;
import io.ktor.server.response.ResponseHeaders;
import io.ktor.server.routing.Route;
import io.ktor.server.routing.Routing;
import io.ktor.server.routing.RoutingBuilderKt;
import io.ktor.server.routing.RoutingContext;
import io.ktor.server.routing.RoutingRootKt;
import io.ktor.server.websocket.RoutingKt;
import io.ktor.server.websocket.WebSockets;
import io.ktor.util.pipeline.Pipeline;
import java.io.IOException;
import java.io.Writer;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.List;
import kotlin.Metadata;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.coroutines.jvm.internal.DebugMetadata;
import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function3;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import kotlin.text.StringsKt;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonBuilder;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonElementKt;
import kotlinx.serialization.json.JsonKt;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;

/* JADX INFO: compiled from: McpServerEngine.android.kt */
/* JADX INFO: loaded from: /private/tmp/overlay-classes2.dex */
@Metadata(d1 = {"\u0000^\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\u000b\n\u0000\n\u0002\u0018\u0002\n\u0002\u0010\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\b\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0018\u0002\n\u0000\b\u0007\u0018\u00002\u00020\u0001B\u008b\u0001\u0012\f\u0010\u0002\u001a\b\u0012\u0004\u0012\u00020\u00040\u0003\u0012\f\u0010\u0005\u001a\b\u0012\u0004\u0012\u00020\u00060\u0003\u0012\u0018\u0010\u0007\u001a\u0014\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\t0\b\u0012\u0018\u0010\n\u001a\u0014\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\t0\b\u0012\u0012\u0010\u000b\u001a\u000e\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\t0\f\u0012\u001e\u0010\r\u001a\u001a\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\t0\u000e¢\u0006\u0004\b\u000f\u0010\u0010J\u0006\u0010\u0015\u001a\u00020\tJ\u001c\u0010\u0016\u001a\u00020\t*\u00020\u00172\u0006\u0010\u0018\u001a\u00020\u00042\u0006\u0010\u0019\u001a\u00020\u001aH\u0002J\u001a\u0010\u001b\u001a\u00020\t*\u00020\u001c2\u0006\u0010\u0019\u001a\u00020\u001aH\u0082@¢\u0006\u0002\u0010\u001dJ\u0018\u0010\u001e\u001a\u00020\u00042\u0006\u0010\u001f\u001a\u00020\u00042\u0006\u0010 \u001a\u00020\u0004H\u0002J\u0006\u0010!\u001a\u00020\tR\u0014\u0010\u0002\u001a\b\u0012\u0004\u0012\u00020\u00040\u0003X\u0082\u0004¢\u0006\u0002\n\u0000R\u0014\u0010\u0005\u001a\b\u0012\u0004\u0012\u00020\u00060\u0003X\u0082\u0004¢\u0006\u0002\n\u0000R \u0010\u0007\u001a\u0014\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\t0\bX\u0082\u0004¢\u0006\u0002\n\u0000R \u0010\n\u001a\u0014\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\t0\bX\u0082\u0004¢\u0006\u0002\n\u0000R\u001a\u0010\u000b\u001a\u000e\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\t0\fX\u0082\u0004¢\u0006\u0002\n\u0000R&\u0010\r\u001a\u001a\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\t0\u000eX\u0082\u0004¢\u0006\u0002\n\u0000R\u0018\u0010\u0011\u001a\f\u0012\u0002\b\u0003\u0012\u0002\b\u0003\u0018\u00010\u0012X\u0082\u000e¢\u0006\u0002\n\u0000R\u000e\u0010\u0013\u001a\u00020\u0014X\u0082\u0004¢\u0006\u0002\n\u0000Ê\u0001\f\b#\u0012\b\b$\u0012\u0004\b\u0003\u0010\u0000¨\u0006\""}, d2 = {"Lcom/vlad/mcpserver/McpServerEngine;", "", "getToken", "Lkotlin/Function0;", "", "isSslEnabled", "", "onClientPending", "Lkotlin/Function2;", "", "onClientAuthorized", "onClientDisconnected", "Lkotlin/Function1;", "onToolExecuted", "Lkotlin/Function3;", "<init>", "(Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function2;Lkotlin/jvm/functions/Function2;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function3;)V", "embeddedServer", "Lio/ktor/server/engine/EmbeddedServer;", "json", "Lkotlinx/serialization/json/Json;", "start", "configureServerModule", "Lio/ktor/server/application/Application;", "protocol", "targetPort", "", "handleSseConnection", "Lio/ktor/server/routing/RoutingContext;", "(Lio/ktor/server/routing/RoutingContext;ILkotlin/coroutines/Continuation;)Ljava/lang/Object;", "processMcpJsonRpcRequest", "clientId", "rawJson", "stop", "MCPServer:shared", "Landroidx/compose/runtime/internal/StabilityInferred;", "parameters"}, k = 1, mv = {2, 4, 0}, xi = 48)
public final class McpServerEngine {
    public static final int $stable = 8;
    private EmbeddedServer<?, ?> embeddedServer;
    private final Function0<String> getToken;
    private final Function0<Boolean> isSslEnabled;
    private final Json json;
    private final Function2<String, String, Unit> onClientAuthorized;
    private final Function1<String, Unit> onClientDisconnected;
    private final Function2<String, String, Unit> onClientPending;
    private final Function3<String, String, String, Unit> onToolExecuted;

    /* JADX WARN: Multi-variable type inference failed */
    public McpServerEngine(Function0<String> function0, Function0<Boolean> function1, Function2<? super String, ? super String, Unit> function2, Function2<? super String, ? super String, Unit> function3, Function1<? super String, Unit> function4, Function3<? super String, ? super String, ? super String, Unit> function5) {
        Intrinsics.checkNotNullParameter(function0, "getToken");
        Intrinsics.checkNotNullParameter(function1, "isSslEnabled");
        Intrinsics.checkNotNullParameter(function2, "onClientPending");
        Intrinsics.checkNotNullParameter(function3, "onClientAuthorized");
        Intrinsics.checkNotNullParameter(function4, "onClientDisconnected");
        Intrinsics.checkNotNullParameter(function5, "onToolExecuted");
        this.getToken = function0;
        this.isSslEnabled = function1;
        this.onClientPending = function2;
        this.onClientAuthorized = function3;
        this.onClientDisconnected = function4;
        this.onToolExecuted = function5;
        this.json = JsonKt.Json$default((Json) null, new Function1() { // from class: com.vlad.mcpserver.McpServerEngine$$ExternalSyntheticLambda2
            public final Object invoke(Object obj) {
                return McpServerEngine.json$lambda$0((JsonBuilder) obj);
            }
        }, 1, (Object) null);
    }

    public static final /* synthetic */ Object access$handleSseConnection(McpServerEngine $this, RoutingContext $receiver, int targetPort, Continuation $completion) {
        return $this.handleSseConnection($receiver, targetPort, $completion);
    }

    static final Unit json$lambda$0(JsonBuilder $this$Json) {
        Intrinsics.checkNotNullParameter($this$Json, "$this$Json");
        $this$Json.setIgnoreUnknownKeys(true);
        return Unit.INSTANCE;
    }

    public final void start() {
        if (this.embeddedServer != null) {
            return;
        }
        try {
            boolean ssl = ((Boolean) this.isSslEnabled.invoke()).booleanValue();
            final int targetPort = ssl ? 8443 : 8080;
            final String protocol = ssl ? "HTTPS/WSS" : "HTTP/WS";
            if (ssl) {
                this.embeddedServer = EmbeddedServerKt.embeddedServer$default(Netty.INSTANCE, (ApplicationEnvironment) null, new Function1() { // from class: com.vlad.mcpserver.McpServerEngine$$ExternalSyntheticLambda4
                    public final Object invoke(Object obj) {
                        return McpServerEngine.start$lambda$0(targetPort, (NettyApplicationEngine.Configuration) obj);
                    }
                }, new Function1() { // from class: com.vlad.mcpserver.McpServerEngine$$ExternalSyntheticLambda5
                    public final Object invoke(Object obj) {
                        return McpServerEngine.start$lambda$1(this.f$0, protocol, targetPort, (Application) obj);
                    }
                }, 2, (Object) null).start(false);
            } else {
                this.embeddedServer = EmbeddedServerKt.embeddedServer$default(CIO.INSTANCE, (ApplicationEnvironment) null, new Function1() { // from class: com.vlad.mcpserver.McpServerEngine$$ExternalSyntheticLambda6
                    public final Object invoke(Object obj) {
                        return McpServerEngine.start$lambda$2(targetPort, (CIOApplicationEngine.Configuration) obj);
                    }
                }, new Function1() { // from class: com.vlad.mcpserver.McpServerEngine$$ExternalSyntheticLambda7
                    public final Object invoke(Object obj) {
                        return McpServerEngine.start$lambda$3(this.f$0, protocol, targetPort, (Application) obj);
                    }
                }, 2, (Object) null).start(false);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    static final Unit start$lambda$0(int $targetPort, NettyApplicationEngine.Configuration $this$embeddedServer) throws NoSuchAlgorithmException, IOException, KeyStoreException, CertificateException {
        Intrinsics.checkNotNullParameter($this$embeddedServer, "$this$embeddedServer");
        ApplicationEngine.Configuration $this$sslConnector$iv = (ApplicationEngine.Configuration) $this$embeddedServer;
        KeyStore $this$start_u24lambda_u240_u240 = KeyStore.getInstance("PKCS12");
        $this$start_u24lambda_u240_u240.load(null, null);
        Intrinsics.checkNotNullExpressionValue($this$start_u24lambda_u240_u240, "apply(...)");
        Function0 keyStorePassword$iv = new Function0() { // from class: com.vlad.mcpserver.McpServerEngine$$ExternalSyntheticLambda0
            public final Object invoke() {
                return McpServerEngine.start$lambda$0$1();
            }
        };
        Function0 privateKeyPassword$iv = new Function0() { // from class: com.vlad.mcpserver.McpServerEngine$$ExternalSyntheticLambda1
            public final Object invoke() {
                return McpServerEngine.start$lambda$0$2();
            }
        };
        List connectors = $this$sslConnector$iv.getConnectors();
        EngineSSLConnectorBuilder $this$start_u24lambda_u240_u243 = new EngineSSLConnectorBuilder($this$start_u24lambda_u240_u240, "mcpkey", keyStorePassword$iv, privateKeyPassword$iv);
        $this$start_u24lambda_u240_u243.setPort($targetPort);
        $this$start_u24lambda_u240_u243.setHost("0.0.0.0");
        connectors.add($this$start_u24lambda_u240_u243);
        return Unit.INSTANCE;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final char[] start$lambda$0$1() {
        char[] charArray = "mcp_password".toCharArray();
        Intrinsics.checkNotNullExpressionValue(charArray, "toCharArray(...)");
        return charArray;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final char[] start$lambda$0$2() {
        char[] charArray = "mcp_password".toCharArray();
        Intrinsics.checkNotNullExpressionValue(charArray, "toCharArray(...)");
        return charArray;
    }

    static final Unit start$lambda$1(McpServerEngine this$0, String $protocol, int $targetPort, Application $this$embeddedServer) {
        Intrinsics.checkNotNullParameter($this$embeddedServer, "$this$embeddedServer");
        this$0.configureServerModule($this$embeddedServer, $protocol, $targetPort);
        return Unit.INSTANCE;
    }

    static final Unit start$lambda$2(int $targetPort, CIOApplicationEngine.Configuration $this$embeddedServer) {
        Intrinsics.checkNotNullParameter($this$embeddedServer, "$this$embeddedServer");
        ApplicationEngine.Configuration $this$connector$iv = (ApplicationEngine.Configuration) $this$embeddedServer;
        List connectors = $this$connector$iv.getConnectors();
        EngineConnectorBuilder $this$start_u24lambda_u242_u240 = new EngineConnectorBuilder((ConnectorType) null, 1, (DefaultConstructorMarker) null);
        $this$start_u24lambda_u242_u240.setPort($targetPort);
        $this$start_u24lambda_u242_u240.setHost("0.0.0.0");
        connectors.add($this$start_u24lambda_u242_u240);
        return Unit.INSTANCE;
    }

    static final Unit start$lambda$3(McpServerEngine this$0, String $protocol, int $targetPort, Application $this$embeddedServer) {
        Intrinsics.checkNotNullParameter($this$embeddedServer, "$this$embeddedServer");
        this$0.configureServerModule($this$embeddedServer, $protocol, $targetPort);
        return Unit.INSTANCE;
    }

    private final void configureServerModule(Application $this$configureServerModule, final String protocol, final int targetPort) {
        ApplicationPluginKt.install$default((Pipeline) $this$configureServerModule, WebSockets.Plugin, (Function1) null, 2, (Object) null);
        RoutingRootKt.routing($this$configureServerModule, new Function1() { // from class: com.vlad.mcpserver.McpServerEngine$$ExternalSyntheticLambda3
            public final Object invoke(Object obj) {
                return McpServerEngine.configureServerModule$lambda$0(protocol, targetPort, this, (Routing) obj);
            }
        });
    }

    static final Unit configureServerModule$lambda$0(String $protocol, int $targetPort, McpServerEngine this$0, Routing $this$routing) {
        Intrinsics.checkNotNullParameter($this$routing, "$this$routing");
        RoutingBuilderKt.get((Route) $this$routing, "/", new McpServerEngine$configureServerModule$1$1($protocol, $targetPort, null));
        RoutingBuilderKt.get((Route) $this$routing, "/status", new McpServerEngine$configureServerModule$1$2(this$0, $targetPort, null));
        RoutingBuilderKt.get((Route) $this$routing, "/sse", new McpServerEngine$configureServerModule$1$3(this$0, $targetPort, null));
        RoutingBuilderKt.get((Route) $this$routing, "/mcp/sse", new McpServerEngine$configureServerModule$1$4(this$0, $targetPort, null));
        RoutingBuilderKt.post((Route) $this$routing, "/message", new McpServerEngine$configureServerModule$1$5(this$0, null));
        RoutingKt.webSocket$default((Route) $this$routing, "/mcp", (String) null, new McpServerEngine$configureServerModule$1$6(this$0, null), 2, (Object) null);
        return Unit.INSTANCE;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final Object handleSseConnection(RoutingContext $this$handleSseConnection, int targetPort, Continuation<? super Unit> continuation) {
        String authHeader = $this$handleSseConnection.getCall().getRequest().getHeaders().get("Authorization");
        String clientId = "copilot-agent-" + System.currentTimeMillis();
        String clientName = $this$handleSseConnection.getCall().getRequest().getHeaders().get("X-Client-Name");
        if (clientName == null) {
            clientName = "GitHub Copilot Agent";
        }
        String configuredToken = (String) this.getToken.invoke();
        boolean isAuthenticated = authHeader != null && Intrinsics.areEqual(authHeader, new StringBuilder().append("Bearer ").append(configuredToken).toString());
        if (isAuthenticated) {
            this.onClientAuthorized.invoke(clientId, clientName);
        } else {
            this.onClientPending.invoke(clientId, clientName);
        }
        String scheme = ((Boolean) this.isSslEnabled.invoke()).booleanValue() ? "https" : "http";
        String ip = StringsKt.trim(Platform_androidKt.getLocalIpAddress()).toString();
        String fullEndpointUrl = scheme + "://" + ip + ":" + targetPort + "/message?sessionId=" + clientId;
        ResponseHeaders.append$default($this$handleSseConnection.getCall().getResponse().getHeaders(), HttpHeaders.INSTANCE.getCacheControl(), "no-cache", false, 4, (Object) null);
        Object objRespondTextWriter$default = ApplicationResponseFunctionsJvmKt.respondTextWriter$default($this$handleSseConnection.getCall(), ContentType.Text.INSTANCE.getEventStream(), (HttpStatusCode) null, new AnonymousClass2(fullEndpointUrl, null), continuation, 2, (Object) null);
        return objRespondTextWriter$default == IntrinsicsKt.getCOROUTINE_SUSPENDED() ? objRespondTextWriter$default : Unit.INSTANCE;
    }

    /* JADX INFO: renamed from: com.vlad.mcpserver.McpServerEngine$handleSseConnection$2, reason: invalid class name */
    /* JADX INFO: compiled from: McpServerEngine.android.kt */
    @Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0010\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u00020\u0001*\u00020\u0002H\n"}, d2 = {"<anonymous>", "", "Ljava/io/Writer;"}, k = 3, mv = {2, 4, 0}, xi = 1328)
    @DebugMetadata(c = "com.vlad.mcpserver.McpServerEngine$handleSseConnection$2", f = "McpServerEngine.android.kt", i = {}, l = {}, m = "invokeSuspend", n = {}, nl = {}, s = {}, v = 2)
    static final class AnonymousClass2 extends SuspendLambda implements Function2<Writer, Continuation<? super Unit>, Object> {
        final /* synthetic */ String $fullEndpointUrl;
        private /* synthetic */ Object L$0;
        int label;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        AnonymousClass2(String str, Continuation<? super AnonymousClass2> continuation) {
            super(2, continuation);
            this.$fullEndpointUrl = str;
        }

        public final Continuation<Unit> create(Object obj, Continuation<?> continuation) {
            Continuation<Unit> anonymousClass2 = new AnonymousClass2(this.$fullEndpointUrl, continuation);
            anonymousClass2.L$0 = obj;
            return anonymousClass2;
        }

        public final Object invoke(Writer writer, Continuation<? super Unit> continuation) {
            return create(writer, continuation).invokeSuspend(Unit.INSTANCE);
        }

        public final Object invokeSuspend(Object $result) throws IOException {
            Writer $this$respondTextWriter = (Writer) this.L$0;
            IntrinsicsKt.getCOROUTINE_SUSPENDED();
            switch (this.label) {
                case 0:
                    ResultKt.throwOnFailure($result);
                    $this$respondTextWriter.write("event: endpoint\ndata: " + this.$fullEndpointUrl + "\n\n");
                    $this$respondTextWriter.flush();
                    return Unit.INSTANCE;
                default:
                    throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Failed to restore switch over string. Please report as a decompilation issue */
    public final String processMcpJsonRpcRequest(String clientId, String rawJson) {
        String method;
        String toolName;
        String arguments;
        String resultText;
        JsonElement jsonElement;
        JsonElement jsonElement2;
        JsonPrimitive jsonPrimitive;
        JsonPrimitive jsonPrimitive2;
        try {
            JsonElement jsonElement3 = this.json.parseToJsonElement(rawJson);
            JsonObject jsonObject = JsonElementKt.getJsonObject(jsonElement3);
            JsonElement id = (JsonElement) jsonObject.get("id");
            if (id == null) {
                id = (JsonElement) JsonElementKt.JsonPrimitive((Number) 1);
            }
            JsonElement jsonElement4 = (JsonElement) jsonObject.get("method");
            if (jsonElement4 == null || (jsonPrimitive2 = JsonElementKt.getJsonPrimitive(jsonElement4)) == null || (method = jsonPrimitive2.getContent()) == null) {
                method = "";
            }
            switch (method.hashCode()) {
                case -729711571:
                    if (method.equals("notifications/initialized")) {
                        return "";
                    }
                    break;
                case 3441010:
                    if (method.equals("ping")) {
                        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}";
                    }
                    break;
                case 498659858:
                    if (method.equals("tools/call")) {
                        JsonElement jsonElement5 = (JsonElement) jsonObject.get("params");
                        JsonObject params = jsonElement5 != null ? JsonElementKt.getJsonObject(jsonElement5) : null;
                        if (params == null || (jsonElement2 = (JsonElement) params.get("name")) == null || (jsonPrimitive = JsonElementKt.getJsonPrimitive(jsonElement2)) == null || (toolName = jsonPrimitive.getContent()) == null) {
                            toolName = "unknown";
                        }
                        if (params == null || (jsonElement = (JsonElement) params.get("arguments")) == null || (arguments = jsonElement.toString()) == null) {
                            arguments = "{}";
                        }
                        switch (toolName.hashCode()) {
                            case -1597059694:
                                resultText = !toolName.equals("touch_test") ? "Tool " + toolName + " executed successfully with args: " + arguments : "Touch event simulated successfully on coordinates " + arguments;
                                break;
                            case -1113857403:
                                if (toolName.equals("read_file")) {
                                    resultText = "File read successfully: /storage/emulated/0/Documents/config.json";
                                }
                                break;
                            case -778038150:
                                if (toolName.equals("take_photo")) {
                                    resultText = "Snapshot photo taken successfully and saved to gallery.";
                                }
                                break;
                            case -685061298:
                                if (toolName.equals("create_notification")) {
                                    resultText = "System notification posted on Android status bar.";
                                }
                                break;
                            default:
                                break;
                        }
                        this.onToolExecuted.invoke(clientId, toolName, arguments);
                        return StringsKt.trimIndent("\n                    {\n                      \"jsonrpc\": \"2.0\",\n                      \"id\": " + id + ",\n                      \"result\": {\n                        \"content\": [\n                          {\n                            \"type\": \"text\",\n                            \"text\": \"" + resultText + "\"\n                          }\n                        ]\n                      }\n                    }\n                    ");
                    }
                    break;
                case 498935890:
                    if (method.equals("tools/list")) {
                        this.onToolExecuted.invoke(clientId, "tools/list", "Requested tool manifest");
                        return StringsKt.trimIndent("\n                    {\n                      \"jsonrpc\": \"2.0\",\n                      \"id\": " + id + ",\n                      \"result\": {\n                        \"tools\": [\n                          {\n                            \"name\": \"touch_test\",\n                            \"description\": \"Simulate a touch interaction on the Android device screen\",\n                            \"inputSchema\": {\n                              \"type\": \"object\",\n                              \"properties\": {\n                                \"x\": { \"type\": \"integer\", \"description\": \"X coordinate\" },\n                                \"y\": { \"type\": \"integer\", \"description\": \"Y coordinate\" }\n                              }\n                            }\n                          },\n                          {\n                            \"name\": \"take_photo\",\n                            \"description\": \"Capture a photo using the Android device camera\",\n                            \"inputSchema\": {\n                              \"type\": \"object\",\n                              \"properties\": {\n                                \"quality\": { \"type\": \"string\" }\n                              }\n                            }\n                          },\n                          {\n                            \"name\": \"read_file\",\n                            \"description\": \"Read file contents from the Android file system\",\n                            \"inputSchema\": {\n                              \"type\": \"object\",\n                              \"properties\": {\n                                \"path\": { \"type\": \"string\", \"description\": \"File path\" }\n                              },\n                              \"required\": [\"path\"]\n                            }\n                          },\n                          {\n                            \"name\": \"create_notification\",\n                            \"description\": \"Post a system notification on the Android device\",\n                            \"inputSchema\": {\n                              \"type\": \"object\",\n                              \"properties\": {\n                                \"title\": { \"type\": \"string\" },\n                                \"message\": { \"type\": \"string\" }\n                              },\n                              \"required\": [\"title\"]\n                            }\n                          }\n                        ]\n                      }\n                    }\n                    ");
                    }
                    break;
                case 871091088:
                    if (method.equals("initialize")) {
                        this.onToolExecuted.invoke(clientId, "initialize", "MCP client initialized");
                        return StringsKt.trimIndent("\n                    {\n                      \"jsonrpc\": \"2.0\",\n                      \"id\": " + id + ",\n                      \"result\": {\n                        \"protocolVersion\": \"2024-11-05\",\n                        \"capabilities\": {\n                          \"tools\": {}\n                        },\n                        \"serverInfo\": {\n                          \"name\": \"MCPServer-Android\",\n                          \"version\": \"0.0.1\"\n                        }\n                      }\n                    }\n                    ");
                    }
                    break;
            }
            this.onToolExecuted.invoke(clientId, method, rawJson);
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}";
        } catch (Exception e) {
            e.printStackTrace();
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,\"message\":\"" + e.getMessage() + "\"}}";
        }
    }

    public final void stop() {
        try {
            EmbeddedServer<?, ?> embeddedServer = this.embeddedServer;
            if (embeddedServer != null) {
                embeddedServer.stop(1000L, 2000L);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        this.embeddedServer = null;
    }
}
