package com.vlad.mcpserver;

import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.SnapshotMutationPolicy;
import androidx.compose.runtime.SnapshotStateKt;
import androidx.compose.runtime.State;
import androidx.lifecycle.ViewModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import kotlin.Metadata;
import kotlin.Unit;
import kotlin.collections.CollectionsKt;
import kotlin.collections.IntIterator;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function3;
import kotlin.jvm.internal.Intrinsics;
import kotlin.random.Random;
import kotlin.ranges.IntRange;
import kotlin.text.StringsKt;

/* JADX INFO: compiled from: AppViewModel.kt */
/* JADX INFO: loaded from: /private/tmp/overlay-classes2.dex */
@Metadata(d1 = {"\u0000N\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0010\u000b\n\u0002\b\n\n\u0002\u0010\u000e\n\u0002\b\u0014\n\u0002\u0018\u0002\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0002\n\u0002\b\u0013\n\u0002\u0018\u0002\n\u0000\b\u0007\u0018\u00002\u00020\u0001B\u0007¢\u0006\u0004\b\u0002\u0010\u0003J\u0006\u00104\u001a\u000205J\u000e\u00106\u001a\u0002052\u0006\u00107\u001a\u00020\u0010J\u0006\u00108\u001a\u00020\u0010J\u000e\u00109\u001a\u0002052\u0006\u0010:\u001a\u00020\u0005J\u000e\u0010;\u001a\u0002052\u0006\u0010:\u001a\u00020\u0005J\u0006\u0010<\u001a\u000205J\u000e\u0010=\u001a\u0002052\u0006\u0010>\u001a\u00020\u0010J\u000e\u0010?\u001a\u0002052\u0006\u0010>\u001a\u00020\u0010J \u0010@\u001a\u0002052\u0006\u0010A\u001a\u00020\u00102\u0006\u0010B\u001a\u00020\u00102\b\b\u0002\u0010>\u001a\u00020\u0010J0\u0010C\u001a\u0002052\u0006\u0010D\u001a\u00020\u00102\u0006\u0010A\u001a\u00020\u00102\u0006\u0010B\u001a\u00020\u00102\u0006\u0010E\u001a\u00020\u00102\u0006\u0010F\u001a\u00020\u0010H\u0002J\b\u0010G\u001a\u000205H\u0002R+\u0010\u0006\u001a\u00020\u00052\u0006\u0010\u0004\u001a\u00020\u00058F@BX\u0086\u008e\u0002¢\u0006\u0012\n\u0004\b\n\u0010\u000b\u001a\u0004\b\u0006\u0010\u0007\"\u0004\b\b\u0010\tR+\u0010\f\u001a\u00020\u00052\u0006\u0010\u0004\u001a\u00020\u00058F@FX\u0086\u008e\u0002¢\u0006\u0012\n\u0004\b\u000f\u0010\u000b\u001a\u0004\b\r\u0010\u0007\"\u0004\b\u000e\u0010\tR+\u0010\u0011\u001a\u00020\u00102\u0006\u0010\u0004\u001a\u00020\u00108F@FX\u0086\u008e\u0002¢\u0006\u0012\n\u0004\b\u0016\u0010\u000b\u001a\u0004\b\u0012\u0010\u0013\"\u0004\b\u0014\u0010\u0015R+\u0010\u0017\u001a\u00020\u00052\u0006\u0010\u0004\u001a\u00020\u00058F@FX\u0086\u008e\u0002¢\u0006\u0012\n\u0004\b\u001a\u0010\u000b\u001a\u0004\b\u0018\u0010\u0007\"\u0004\b\u0019\u0010\tR+\u0010\u001b\u001a\u00020\u00052\u0006\u0010\u0004\u001a\u00020\u00058F@FX\u0086\u008e\u0002¢\u0006\u0012\n\u0004\b\u001d\u0010\u000b\u001a\u0004\b\u001b\u0010\u0007\"\u0004\b\u001c\u0010\tR\u0014\u0010\u001e\u001a\u00020\u0010X\u0086D¢\u0006\b\n\u0000\u001a\u0004\b\u001f\u0010\u0013R\u0011\u0010 \u001a\u00020\u00108F¢\u0006\u0006\u001a\u0004\b!\u0010\u0013R\u0011\u0010\"\u001a\u00020\u00108F¢\u0006\u0006\u001a\u0004\b#\u0010\u0013R6\u0010(\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020'0&0%2\u0012\u0010$\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020'0&0%@BX\u0086\u000e¢\u0006\b\n\u0000\u001a\u0004\b)\u0010*R6\u0010+\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020'0&0%2\u0012\u0010$\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020'0&0%@BX\u0086\u000e¢\u0006\b\n\u0000\u001a\u0004\b,\u0010*R6\u0010.\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020-0&0%2\u0012\u0010$\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020-0&0%@BX\u0086\u000e¢\u0006\b\n\u0000\u001a\u0004\b/\u0010*R\u0010\u00100\u001a\u0004\u0018\u000101X\u0082\u000e¢\u0006\u0002\n\u0000R\u000e\u00102\u001a\u000203X\u0082\u0004¢\u0006\u0002\n\u0000Ê\u0001\f\bI\u0012\b\bJ\u0012\u0004\b\u0003\u0010\u0000¨\u0006H"}, d2 = {"Lcom/vlad/mcpserver/AppViewModel;", "Landroidx/lifecycle/ViewModel;", "<init>", "()V", "<set-?>", "", "isServerRunning", "()Z", "setServerRunning", "(Z)V", "isServerRunning$delegate", "Landroidx/compose/runtime/MutableState;", "showSettings", "getShowSettings", "setShowSettings", "showSettings$delegate", "", "bearerToken", "getBearerToken", "()Ljava/lang/String;", "setBearerToken", "(Ljava/lang/String;)V", "bearerToken$delegate", "sslEnabled", "getSslEnabled", "setSslEnabled", "sslEnabled$delegate", "isDarkMode", "setDarkMode", "isDarkMode$delegate", "serverName", "getServerName", "connectionUrl", "getConnectionUrl", "sseUrl", "getSseUrl", "value", "Landroidx/compose/runtime/MutableState;", "", "Lcom/vlad/mcpserver/AiClient;", "connectedClients", "getConnectedClients", "()Landroidx/compose/runtime/MutableState;", "pendingClients", "getPendingClients", "Lcom/vlad/mcpserver/ToolExecutionLog;", "executionLogs", "getExecutionLogs", "database", "Lcom/vlad/mcpserver/AppDatabase;", "mcpServerEngine", "Lcom/vlad/mcpserver/McpServerEngine;", "toggleSettings", "", "updateBearerToken", "newToken", "generateSecureToken", "updateSslEnabled", "enabled", "updateDarkMode", "toggleServer", "approveClient", "clientId", "rejectClient", "simulateAiToolCall", "toolName", "arguments", "logCommand", "aiClientId", "status", "result", "loadHistory", "MCPServer:shared", "Landroidx/compose/runtime/internal/StabilityInferred;", "parameters"}, k = 1, mv = {2, 4, 0}, xi = 48)
public final class AppViewModel extends ViewModel {
    public static final int $stable = 8;
    private AppDatabase database;

    /* JADX INFO: renamed from: isServerRunning$delegate, reason: from kotlin metadata */
    private final MutableState isServerRunning = SnapshotStateKt.mutableStateOf$default(false, (SnapshotMutationPolicy) null, 2, (Object) null);

    /* JADX INFO: renamed from: showSettings$delegate, reason: from kotlin metadata */
    private final MutableState showSettings = SnapshotStateKt.mutableStateOf$default(false, (SnapshotMutationPolicy) null, 2, (Object) null);

    /* JADX INFO: renamed from: bearerToken$delegate, reason: from kotlin metadata */
    private final MutableState bearerToken = SnapshotStateKt.mutableStateOf$default("mcp_secret_token_2025", (SnapshotMutationPolicy) null, 2, (Object) null);

    /* JADX INFO: renamed from: sslEnabled$delegate, reason: from kotlin metadata */
    private final MutableState sslEnabled = SnapshotStateKt.mutableStateOf$default(false, (SnapshotMutationPolicy) null, 2, (Object) null);

    /* JADX INFO: renamed from: isDarkMode$delegate, reason: from kotlin metadata */
    private final MutableState isDarkMode = SnapshotStateKt.mutableStateOf$default(true, (SnapshotMutationPolicy) null, 2, (Object) null);
    private final String serverName = "MCPServer Host";
    private MutableState<List<AiClient>> connectedClients = SnapshotStateKt.mutableStateOf$default(CollectionsKt.emptyList(), (SnapshotMutationPolicy) null, 2, (Object) null);
    private MutableState<List<AiClient>> pendingClients = SnapshotStateKt.mutableStateOf$default(CollectionsKt.emptyList(), (SnapshotMutationPolicy) null, 2, (Object) null);
    private MutableState<List<ToolExecutionLog>> executionLogs = SnapshotStateKt.mutableStateOf$default(CollectionsKt.emptyList(), (SnapshotMutationPolicy) null, 2, (Object) null);
    private final McpServerEngine mcpServerEngine = new McpServerEngine(new Function0() { // from class: com.vlad.mcpserver.AppViewModel$$ExternalSyntheticLambda0
        public final Object invoke() {
            return this.f$0.getBearerToken();
        }
    }, new Function0() { // from class: com.vlad.mcpserver.AppViewModel$$ExternalSyntheticLambda1
        public final Object invoke() {
            return Boolean.valueOf(this.f$0.getSslEnabled());
        }
    }, new Function2() { // from class: com.vlad.mcpserver.AppViewModel$$ExternalSyntheticLambda2
        public final Object invoke(Object obj, Object obj2) {
            return AppViewModel.mcpServerEngine$lambda$2(this.f$0, (String) obj, (String) obj2);
        }
    }, new Function2() { // from class: com.vlad.mcpserver.AppViewModel$$ExternalSyntheticLambda3
        public final Object invoke(Object obj, Object obj2) {
            return AppViewModel.mcpServerEngine$lambda$3(this.f$0, (String) obj, (String) obj2);
        }
    }, new Function1() { // from class: com.vlad.mcpserver.AppViewModel$$ExternalSyntheticLambda4
        public final Object invoke(Object obj) {
            return AppViewModel.mcpServerEngine$lambda$4(this.f$0, (String) obj);
        }
    }, new Function3() { // from class: com.vlad.mcpserver.AppViewModel$$ExternalSyntheticLambda5
        public final Object invoke(Object obj, Object obj2, Object obj3) {
            return AppViewModel.mcpServerEngine$lambda$5(this.f$0, (String) obj, (String) obj2, (String) obj3);
        }
    });

    public AppViewModel() {
        try {
            String path = Platform_androidKt.getDatabasePath("mcp_server.db");
            AppDatabase db = new AppDatabase(path);
            db.initDatabase();
            this.database = db;
            String it = db.getSetting("bearer_token");
            if (it != null) {
                setBearerToken(it);
            }
            String it2 = db.getSetting("ssl_enabled");
            if (it2 != null) {
                setSslEnabled(Boolean.parseBoolean(it2));
            }
            String it3 = db.getSetting("dark_mode");
            if (it3 != null) {
                setDarkMode(Boolean.parseBoolean(it3));
            }
            loadHistory();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private final void setServerRunning(boolean z) {
        MutableState $this$setValue$iv = this.isServerRunning;
        Object value$iv = Boolean.valueOf(z);
        $this$setValue$iv.setValue(value$iv);
    }

    public final boolean isServerRunning() {
        State $this$getValue$iv = this.isServerRunning;
        return ((Boolean) $this$getValue$iv.getValue()).booleanValue();
    }

    public final boolean getShowSettings() {
        State $this$getValue$iv = this.showSettings;
        return ((Boolean) $this$getValue$iv.getValue()).booleanValue();
    }

    public final void setShowSettings(boolean z) {
        MutableState $this$setValue$iv = this.showSettings;
        Object value$iv = Boolean.valueOf(z);
        $this$setValue$iv.setValue(value$iv);
    }

    public final String getBearerToken() {
        State $this$getValue$iv = this.bearerToken;
        return (String) $this$getValue$iv.getValue();
    }

    public final void setBearerToken(String str) {
        Intrinsics.checkNotNullParameter(str, "<set-?>");
        MutableState $this$setValue$iv = this.bearerToken;
        $this$setValue$iv.setValue(str);
    }

    public final boolean getSslEnabled() {
        State $this$getValue$iv = this.sslEnabled;
        return ((Boolean) $this$getValue$iv.getValue()).booleanValue();
    }

    public final void setSslEnabled(boolean z) {
        MutableState $this$setValue$iv = this.sslEnabled;
        Object value$iv = Boolean.valueOf(z);
        $this$setValue$iv.setValue(value$iv);
    }

    public final boolean isDarkMode() {
        State $this$getValue$iv = this.isDarkMode;
        return ((Boolean) $this$getValue$iv.getValue()).booleanValue();
    }

    public final void setDarkMode(boolean z) {
        MutableState $this$setValue$iv = this.isDarkMode;
        Object value$iv = Boolean.valueOf(z);
        $this$setValue$iv.setValue(value$iv);
    }

    public final String getServerName() {
        return this.serverName;
    }

    public final String getConnectionUrl() {
        String scheme = getSslEnabled() ? "wss" : "ws";
        int port = getSslEnabled() ? 8443 : 8080;
        String ip = StringsKt.trim(Platform_androidKt.getLocalIpAddress()).toString();
        return scheme + "://" + ip + ":" + port + "/mcp";
    }

    public final String getSseUrl() {
        String scheme = getSslEnabled() ? "https" : "http";
        int port = getSslEnabled() ? 8443 : 8080;
        String ip = StringsKt.trim(Platform_androidKt.getLocalIpAddress()).toString();
        return scheme + "://" + ip + ":" + port + "/sse";
    }

    public final MutableState<List<AiClient>> getConnectedClients() {
        return this.connectedClients;
    }

    public final MutableState<List<AiClient>> getPendingClients() {
        return this.pendingClients;
    }

    public final MutableState<List<ToolExecutionLog>> getExecutionLogs() {
        return this.executionLogs;
    }

    static final Unit mcpServerEngine$lambda$2(AppViewModel this$0, String id, String name) {
        Intrinsics.checkNotNullParameter(id, "id");
        Intrinsics.checkNotNullParameter(name, "name");
        AiClient client = new AiClient(id, name, "Pending Approval", 1700000000L);
        Iterable $this$any$iv = (Iterable) this$0.pendingClients.getValue();
        boolean z = false;
        if (!($this$any$iv instanceof Collection) || !((Collection) $this$any$iv).isEmpty()) {
            for (Object element$iv : $this$any$iv) {
                AiClient it = (AiClient) element$iv;
                if (Intrinsics.areEqual(it.getId(), id)) {
                    z = true;
                    break;
                }
            }
        }
        if (!z) {
            this$0.pendingClients.setValue(CollectionsKt.plus((Collection) this$0.pendingClients.getValue(), client));
        }
        this$0.logCommand(id, "AuthPending", "Client requested access without valid token", "WARNING", "Pending manual approval.");
        return Unit.INSTANCE;
    }

    static final Unit mcpServerEngine$lambda$3(AppViewModel this$0, String id, String name) {
        Intrinsics.checkNotNullParameter(id, "id");
        Intrinsics.checkNotNullParameter(name, "name");
        AiClient client = new AiClient(id, name, "Authenticated", 1700000000L);
        Iterable $this$any$iv = (Iterable) this$0.connectedClients.getValue();
        boolean z = false;
        if (!($this$any$iv instanceof Collection) || !((Collection) $this$any$iv).isEmpty()) {
            for (Object element$iv : $this$any$iv) {
                AiClient it = (AiClient) element$iv;
                if (Intrinsics.areEqual(it.getId(), id)) {
                    z = true;
                    break;
                }
            }
        }
        if (!z) {
            this$0.connectedClients.setValue(CollectionsKt.plus((Collection) this$0.connectedClients.getValue(), client));
        }
        this$0.logCommand(id, "AuthSuccess", "Client authenticated via Bearer Token", "SUCCESS", "Connection authorized.");
        return Unit.INSTANCE;
    }

    static final Unit mcpServerEngine$lambda$4(AppViewModel this$0, String id) {
        Intrinsics.checkNotNullParameter(id, "id");
        MutableState<List<AiClient>> mutableState = this$0.connectedClients;
        Iterable $this$filter$iv = (Iterable) this$0.connectedClients.getValue();
        Collection destination$iv$iv = new ArrayList();
        for (Object element$iv$iv : $this$filter$iv) {
            AiClient it = (AiClient) element$iv$iv;
            if (!Intrinsics.areEqual(it.getId(), id)) {
                destination$iv$iv.add(element$iv$iv);
            }
        }
        mutableState.setValue((List) destination$iv$iv);
        MutableState<List<AiClient>> mutableState2 = this$0.pendingClients;
        Iterable $this$filter$iv2 = (Iterable) this$0.pendingClients.getValue();
        Collection destination$iv$iv2 = new ArrayList();
        for (Object element$iv$iv2 : $this$filter$iv2) {
            AiClient it2 = (AiClient) element$iv$iv2;
            if (!Intrinsics.areEqual(it2.getId(), id)) {
                destination$iv$iv2.add(element$iv$iv2);
            }
        }
        mutableState2.setValue((List) destination$iv$iv2);
        this$0.logCommand(id, "Disconnect", "Client disconnected", "SUCCESS", "Connection closed.");
        return Unit.INSTANCE;
    }

    static final Unit mcpServerEngine$lambda$5(AppViewModel this$0, String clientId, String toolName, String args) {
        Intrinsics.checkNotNullParameter(clientId, "clientId");
        Intrinsics.checkNotNullParameter(toolName, "toolName");
        Intrinsics.checkNotNullParameter(args, "args");
        this$0.simulateAiToolCall(toolName, args, clientId);
        return Unit.INSTANCE;
    }

    public final void toggleSettings() {
        setShowSettings(!getShowSettings());
    }

    public final void updateBearerToken(String newToken) {
        Intrinsics.checkNotNullParameter(newToken, "newToken");
        setBearerToken(newToken);
        AppDatabase appDatabase = this.database;
        if (appDatabase != null) {
            appDatabase.saveSetting("bearer_token", newToken);
        }
    }

    public final String generateSecureToken() {
        Iterable $this$map$iv = new IntRange(1, 14);
        Collection destination$iv$iv = new ArrayList(CollectionsKt.collectionSizeOrDefault($this$map$iv, 10));
        IntIterator it = $this$map$iv.iterator();
        while (it.hasNext()) {
            it.nextInt();
            int it2 = Random.Default.nextInt(0, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*".length());
            destination$iv$iv.add(Integer.valueOf(it2));
        }
        Iterable $this$map$iv2 = (List) destination$iv$iv;
        Collection destination$iv$iv2 = new ArrayList(CollectionsKt.collectionSizeOrDefault($this$map$iv2, 10));
        for (Object item$iv$iv : $this$map$iv2) {
            int p0 = ((Number) item$iv$iv).intValue();
            destination$iv$iv2.add(Character.valueOf("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*".charAt(p0)));
        }
        String newToken = CollectionsKt.joinToString$default((List) destination$iv$iv2, "", (CharSequence) null, (CharSequence) null, 0, (CharSequence) null, (Function1) null, 62, (Object) null);
        updateBearerToken(newToken);
        return newToken;
    }

    public final void updateSslEnabled(boolean enabled) {
        setSslEnabled(enabled);
        AppDatabase appDatabase = this.database;
        if (appDatabase != null) {
            appDatabase.saveSetting("ssl_enabled", String.valueOf(enabled));
        }
    }

    public final void updateDarkMode(boolean enabled) {
        setDarkMode(enabled);
        AppDatabase appDatabase = this.database;
        if (appDatabase != null) {
            appDatabase.saveSetting("dark_mode", String.valueOf(enabled));
        }
    }

    public final void toggleServer() {
        setServerRunning(!isServerRunning());
        boolean zIsServerRunning = isServerRunning();
        McpServerEngine mcpServerEngine = this.mcpServerEngine;
        if (zIsServerRunning) {
            mcpServerEngine.start();
            String port = getSslEnabled() ? "8443 (HTTPS/WSS)" : "8080 (HTTP/WS)";
            logCommand("System", "StartServer", "Port: " + port, "SUCCESS", "Tester started successfully.");
        } else {
            mcpServerEngine.stop();
            this.connectedClients.setValue(CollectionsKt.emptyList());
            this.pendingClients.setValue(CollectionsKt.emptyList());
            logCommand("System", "StopServer", "", "SUCCESS", "Tester stopped.");
        }
    }

    public final void approveClient(String clientId) {
        Object next;
        AiClient it;
        Intrinsics.checkNotNullParameter(clientId, "clientId");
        Iterator it2 = ((Iterable) this.pendingClients.getValue()).iterator();
        do {
            if (!it2.hasNext()) {
                next = null;
                break;
            } else {
                next = it2.next();
                it = (AiClient) next;
            }
        } while (!Intrinsics.areEqual(it.getId(), clientId));
        AiClient client = (AiClient) next;
        if (client == null) {
            return;
        }
        MutableState<List<AiClient>> mutableState = this.pendingClients;
        Iterable $this$filter$iv = (Iterable) this.pendingClients.getValue();
        Collection destination$iv$iv = new ArrayList();
        for (Object element$iv$iv : $this$filter$iv) {
            AiClient it3 = (AiClient) element$iv$iv;
            if (!Intrinsics.areEqual(it3.getId(), clientId)) {
                destination$iv$iv.add(element$iv$iv);
            }
        }
        mutableState.setValue((List) destination$iv$iv);
        AiClient approvedClient = AiClient.copy$default(client, null, null, "Manually Approved", 0L, 11, null);
        this.connectedClients.setValue(CollectionsKt.plus((Collection) this.connectedClients.getValue(), approvedClient));
        logCommand(clientId, "ManualApproval", "User approved client connection", "SUCCESS", "Client moved to active.");
    }

    public final void rejectClient(String clientId) {
        Intrinsics.checkNotNullParameter(clientId, "clientId");
        MutableState<List<AiClient>> mutableState = this.pendingClients;
        Iterable $this$filter$iv = (Iterable) this.pendingClients.getValue();
        Collection destination$iv$iv = new ArrayList();
        for (Object element$iv$iv : $this$filter$iv) {
            AiClient it = (AiClient) element$iv$iv;
            if (!Intrinsics.areEqual(it.getId(), clientId)) {
                destination$iv$iv.add(element$iv$iv);
            }
        }
        mutableState.setValue((List) destination$iv$iv);
        logCommand(clientId, "ManualReject", "User rejected client connection", "FAILED", "Connection denied.");
    }

    public static /* synthetic */ void simulateAiToolCall$default(AppViewModel appViewModel, String str, String str2, String str3, int i, Object obj) {
        AiClient aiClient;
        if ((i & 4) != 0 && ((aiClient = (AiClient) CollectionsKt.firstOrNull((List) appViewModel.connectedClients.getValue())) == null || (str3 = aiClient.getId()) == null)) {
            str3 = "unknown-ai";
        }
        appViewModel.simulateAiToolCall(str, str2, str3);
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code duplicated, block: B:21:0x0047  */
    public final void simulateAiToolCall(String toolName, String arguments, String clientId) {
        String str;
        Intrinsics.checkNotNullParameter(toolName, "toolName");
        Intrinsics.checkNotNullParameter(arguments, "arguments");
        Intrinsics.checkNotNullParameter(clientId, "clientId");
        switch (toolName) {
            case "touch_test":
                str = "Touch event simulated at coordinates (500, 800) successfully.";
                break;
            case "read_file":
                str = "File read successfully: /storage/emulated/0/Documents/config.json";
                break;
            case "take_photo":
                str = "Photo captured successfully: /storage/emulated/0/DCIM/mcp_snapshot_01.jpg";
                break;
            case "create_notification":
                str = "System notification posted successfully ID: 101";
                break;
            default:
                str = "Tool executed successfully (" + arguments + ").";
                break;
        }
        String result = str;
        logCommand(clientId, toolName, arguments, "SUCCESS", result);
    }

    private final void logCommand(String aiClientId, String toolName, String arguments, String status, String result) {
        AppDatabase appDatabase = this.database;
        if (appDatabase != null) {
            appDatabase.insertCommandHistory(aiClientId, toolName, arguments, status, result);
        }
        loadHistory();
    }

    private final void loadHistory() {
        AppDatabase db = this.database;
        if (db != null) {
            this.executionLogs.setValue(db.getCommandHistories());
        }
    }
}
