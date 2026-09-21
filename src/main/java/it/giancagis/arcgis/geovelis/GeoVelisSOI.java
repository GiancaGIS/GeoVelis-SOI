package it.giancagis.arcgis.geovelis;

import com.esri.arcgis.interop.AutomationException;
import com.esri.arcgis.interop.extn.ArcGISExtension;
import com.esri.arcgis.interop.extn.ServerObjectExtProperties;
import com.esri.arcgis.server.IServerObject;
import com.esri.arcgis.server.IServerObjectExtension;
import com.esri.arcgis.server.IServerObjectHelper;
import com.esri.arcgis.server.SOIHelper;
import com.esri.arcgis.system.ILog;
import com.esri.arcgis.system.IObjectConstruct;
import com.esri.arcgis.system.IPropertySet;
import com.esri.arcgis.system.IRESTRequestHandler;
import com.esri.arcgis.system.IRequestHandler;
import com.esri.arcgis.system.IRequestHandler2;
import com.esri.arcgis.system.IServerUserInfo;
import com.esri.arcgis.system.IWebRequestHandler;
import com.esri.arcgis.system.ServerUtilities;
import it.giancagis.arcgis.geovelis.audit.ArcGisServerAuditLogger;
import it.giancagis.arcgis.geovelis.audit.AuditLogger;
import it.giancagis.arcgis.geovelis.audit.CompositeAuditLogger;
import it.giancagis.arcgis.geovelis.audit.FileAuditLogger;
import it.giancagis.arcgis.geovelis.config.GeoVelisConfig;
import it.giancagis.arcgis.geovelis.masking.ResponseMasker;
import it.giancagis.arcgis.geovelis.masking.ResponseMaskingResult;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.rules.FieldGuardianRule;
import it.giancagis.arcgis.geovelis.rules.GeometryGuardianRule;
import it.giancagis.arcgis.geovelis.rules.QueryGuardianRule;
import it.giancagis.arcgis.geovelis.rules.Rule;
import it.giancagis.arcgis.geovelis.rules.RuleEngine;
import it.giancagis.arcgis.geovelis.rules.RuleResult;
import it.giancagis.arcgis.geovelis.util.RestErrorResponse;
import it.giancagis.arcgis.geovelis.util.RestResponseProperties;
import it.giancagis.arcgis.geovelis.util.AuditSanitizer;
import it.giancagis.arcgis.geovelis.util.StringUtils;
import it.giancagis.arcgis.geovelis.risk.RiskAnalyzer;
import it.giancagis.arcgis.geovelis.risk.RiskAssessment;
import it.giancagis.arcgis.geovelis.behavior.BehaviorObserver;
import it.giancagis.arcgis.geovelis.behavior.BehaviorBlockGate;

import java.io.IOException;
import java.util.Arrays;

/**
 * GeoVelis SOI
 *
 * REST behavior:
 * - audit incoming/outgoing requests;
 * - inspect query operations;
 * - in audit mode, only log suspicious queries;
 * - in enforce mode, block or modify query requests depending on configuration;
 * - pass through every other REST operation;
 * - optionally masks sensitive attributes in query JSON responses.
 */
@ArcGISExtension
@ServerObjectExtProperties(
        displayName = "GeoVelis SOI",
        description = "Audit, Query Guardian and Response Masking interceptor for ArcGIS Server map services.",
        interceptor = true,
        servicetype = "MapService",
        supportsSharedInstances = false,
        properties = {
                "enabled=true",
                "mode=audit",
                "logOperationInput=true",
                "maxLoggedInputLength=2000",
                "fileAuditEnabled=false",
                "fileAuditDirectory=",
                "fileAuditPrefix=geovelis-audit",
                "fileAuditRetentionDays=30",
                "maxRecordCount=5000",
                "riskEnabled=false",
                "riskProfile=balanced",
                "riskLargeEnvelopeArea=0",
                "riskRedisEndpoint=",
                "riskServiceKey=",
                "riskBlockThreshold=0",
                "geometryGuardMaxEnvelopeArea=0",
                "layerPolicyOverrides=",
                "fieldGuardianEnabled=false",
                "fieldGuardianAllowedOutFields=",
                "fieldGuardianDeniedOutFields=",
                "fieldGuardianBlockOnDeniedFields=false",
                "responseMaskingEnabled=false",
                "responseMaskingFields=CODICE_FISCALE,EMAIL,TELEFONO",
                "responseMaskingReplacement=****",
                "responseMaskingStrategy=full",
                "responseMaskingFieldStrategies=",
                "responseMaskingFailPolicy=keepOriginal",
                "responseMaskingApplyToQueryOnly=true"
        }
)
public class GeoVelisSOI implements IServerObjectExtension, IRESTRequestHandler, IWebRequestHandler,
        IRequestHandler2, IRequestHandler, IObjectConstruct {

    private static final long serialVersionUID = 1L;

    private final String version = "1.0.1";

    private ILog serverLog;
    private IServerObject so;
    private SOIHelper soiHelper;

    private GeoVelisConfig config = GeoVelisConfig.defaults();
    private AuditLogger auditLogger;
    private RuleEngine ruleEngine;
    private ResponseMasker responseMasker;
    private RiskAnalyzer riskAnalyzer;
    private BehaviorObserver behaviorObserver;
    private BehaviorBlockGate behaviorBlockGate;

    public GeoVelisSOI() {
        rebuildRuleEngine();
    }

    @Override
    public void init(IServerObjectHelper soh) throws IOException, AutomationException {
        this.serverLog = ServerUtilities.getServerLogger();
        rebuildAuditLogger();
        this.so = soh.getServerObject();
        this.soiHelper = new SOIHelper();

        rebuildRuleEngine();
        this.auditLogger.info("Initialized GeoVelis SOI v " + version + " " + config);
    }

    @Override
    public void shutdown() throws IOException, AutomationException {
        if (behaviorObserver != null) behaviorObserver.close();
        if (behaviorBlockGate != null) behaviorBlockGate.close();
        if (riskAnalyzer != null) riskAnalyzer.clear();
        if (auditLogger != null) {
            auditLogger.info("Shutdown GeoVelis SOI v " + version);
        }
        if (soiHelper != null) {
            soiHelper.cleanup();
            soiHelper = null;
        }
        so = null;
        serverLog = null;
        auditLogger = null;
    }

    @Override
    public void construct(IPropertySet props) throws IOException, AutomationException {
        try {
            this.config = GeoVelisConfig.fromPropertySet(props);
        } catch (IllegalArgumentException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        rebuildRuleEngine();
        rebuildAuditLogger();

        if (auditLogger != null) {
            auditLogger.info("Configuration loaded. " + config);
        }
    }

    @Override
    public String getSchema() throws IOException, AutomationException {
        IRESTRequestHandler delegate = soiHelper.findRestRequestHandlerDelegate(so);
        if (delegate != null) {
            return delegate.getSchema();
        }
        return "";
    }

    @Override
    public byte[] handleRESTRequest(
            String capabilities,
            String resourceName,
            String operationName,
            String operationInput,
            String outputFormat,
            String requestProperties,
            String[] responseProperties
    ) throws IOException, AutomationException {

        long start = System.currentTimeMillis();
        IRESTRequestHandler restRequestHandler = findRestRequestHandlerDelegate();

        RestRequestContext ctx = new RestRequestContext(
                getCurrentUserNameSafe(),
                capabilities,
                resourceName,
                operationName,
                operationInput,
                outputFormat,
                requestProperties
        );

        if (restRequestHandler == null) {
            if (auditLogger != null) {
                auditLogger.error(ctx, new IllegalStateException("REST delegate not available."), elapsed(start));
            }
            RestErrorResponse.setJsonResponseProperties(responseProperties);
            if (config.isEnabled()) recordRisk(riskAnalyzer, behaviorObserver, ctx, operationInput, 0, 0, RiskAssessment.Outcome.NO_DELEGATE);
            return RestErrorResponse.badRequest("REST delegate not available.");
        }

        if (!config.isEnabled()) {
            return restRequestHandler.handleRESTRequest(
                    capabilities,
                    resourceName,
                    operationName,
                    operationInput,
                    outputFormat,
                    requestProperties,
                    responseProperties
            );
        }

        RiskAnalyzer observer = riskAnalyzer;
        BehaviorObserver behavior = behaviorObserver;
        String riskEffectiveInput = operationInput;
        RiskAssessment.Outcome riskOutcome = RiskAssessment.Outcome.ERROR;
        long riskBytes = 0;
        long riskStart = System.nanoTime();
        try {
            if (auditLogger != null) {
                auditLogger.requestIn(ctx, operationInputForLog(operationInput));
            }

            BehaviorBlockGate.Result block = checkBehaviorBlock(ctx);
            if (block.reportable() && auditLogger != null) auditLogger.warning(block.logMessage());
            if (block.blocked()) {
                RestErrorResponse.setJsonResponseProperties(responseProperties);
                byte[] response = RestErrorResponse.temporarilyBlocked(block.ttlMs());
                riskOutcome = RiskAssessment.Outcome.BEHAVIOR_BLOCKED;
                riskBytes = response.length;
                if (auditLogger != null) auditLogger.responseOut(ctx, elapsed(start), response.length);
                return response;
            }

            RuleResult ruleResult = ruleEngine.before(ctx);
            if (auditLogger != null) {
                auditLogger.rule(ctx, ruleResult);
            }

            if (ruleResult.isBlocked()) {
                RestErrorResponse.setJsonResponseProperties(responseProperties);
                byte[] blockedResponse = RestErrorResponse.badRequest(ruleResult.message());
                riskOutcome = RiskAssessment.Outcome.RULE_BLOCKED;
                riskBytes = blockedResponse.length;
                if (auditLogger != null) {
                    auditLogger.responseOut(ctx, elapsed(start), blockedResponse.length);
                }
                return blockedResponse;
            }

            String effectiveOperationInput = ruleResult.modifiedOperationInput().orElse(operationInput);
            riskEffectiveInput = effectiveOperationInput;

            byte[] response = restRequestHandler.handleRESTRequest(
                    capabilities,
                    resourceName,
                    operationName,
                    effectiveOperationInput,
                    outputFormat,
                    requestProperties,
                    responseProperties
            );

            ResponseMaskingResult maskingResult = responseMasker.process(ctx, response);
            if (auditLogger != null) {
                auditLogger.responseMasking(ctx, maskingResult);
            }

            byte[] effectiveResponse = maskingResult == null ? response : maskingResult.response();
            if (maskingResult != null
                    && maskingResult.decision() == ResponseMaskingResult.Decision.BLOCKED_ON_ERROR) {
                RestErrorResponse.setJsonResponseProperties(responseProperties);
            }

            if (maskingResult != null && maskingResult.decision() == ResponseMaskingResult.Decision.MASKED) {
                RestResponseProperties.forMaskedResponse(responseProperties, outputFormat);
            }

            riskOutcome = maskingResult != null && maskingResult.decision() == ResponseMaskingResult.Decision.BLOCKED_ON_ERROR
                    ? RiskAssessment.Outcome.MASKING_BLOCKED : RiskAssessment.Outcome.RETURNED;
            riskBytes = effectiveResponse == null ? 0 : effectiveResponse.length;

            if (auditLogger != null) {
                auditLogger.responseOut(ctx, elapsed(start), effectiveResponse == null ? 0 : effectiveResponse.length);
            }

            return effectiveResponse;

        } catch (Exception ex) {
            riskOutcome = RiskAssessment.Outcome.ERROR;
            riskBytes = 0;
            if (auditLogger != null) {
                auditLogger.error(ctx, ex, elapsed(start));
            }

            if (ex instanceof AutomationException) {
                throw (AutomationException) ex;
            }
            if (ex instanceof IOException) {
                throw (IOException) ex;
            }
            throw new IOException("GeoVelis SOI error: " + ex.getMessage(), ex);
        } finally {
            recordRisk(observer, behavior, ctx, riskEffectiveInput, riskBytes, (System.nanoTime() - riskStart) / 1000000L, riskOutcome);
        }
    }

    private void recordRisk(RiskAnalyzer observer, BehaviorObserver behavior, RestRequestContext ctx, String effectiveInput, long responseBytes,
                            long elapsedMs, RiskAssessment.Outcome outcome) {
        try {
            RiskAssessment assessment = observer == null ? null : observer.observe(ctx, responseBytes, elapsedMs, outcome);
            if (assessment != null && behavior != null) behavior.submit(ctx, effectiveInput, assessment, responseBytes, outcome);
            if (assessment != null && auditLogger != null) auditLogger.risk(assessment);
        } catch (Exception ignored) {
            // Observation must never change the delegate response or replace its exception.
            try {
                if (auditLogger != null) auditLogger.warning("Risk telemetry unavailable for this request.");
            } catch (Exception loggingFailure) { /* Best effort only. */ }
        }
    }

    /**
     * SOAP binary pass-through.
     */
    @Override
    public byte[] handleBinaryRequest(byte[] request) throws IOException, AutomationException {
        IRequestHandler delegate = soiHelper.findRequestHandlerDelegate(so);
        if (delegate != null) {
            return delegate.handleBinaryRequest(request);
        }
        return null;
    }

    /**
     * SOAP binary pass-through with explicit capabilities.
     */
    @Override
    public byte[] handleBinaryRequest2(String capabilities, byte[] request) throws IOException, AutomationException {
        IRequestHandler2 delegate = soiHelper.findRequestHandler2Delegate(so);
        if (delegate != null) {
            return delegate.handleBinaryRequest2(capabilities, request);
        }
        return handleBinaryRequest(request);
    }

    /**
     * SOAP string pass-through.
     */
    @Override
    public String handleStringRequest(String capabilities, String request) throws IOException, AutomationException {
        IRequestHandler delegate = soiHelper.findRequestHandlerDelegate(so);
        if (delegate != null) {
            return delegate.handleStringRequest(capabilities, request);
        }
        return null;
    }

    /**
     * OGC/web pass-through.
     */
    @Override
    public byte[] handleStringWebRequest(
            int httpMethod,
            String requestURL,
            String queryString,
            String capabilities,
            String requestData,
            String[] responseContentType,
            int[] respDataType
    ) throws IOException, AutomationException {
        IWebRequestHandler delegate = soiHelper.findWebRequestHandlerDelegate(so);
        if (delegate != null) {
            return delegate.handleStringWebRequest(
                    httpMethod,
                    requestURL,
                    queryString,
                    capabilities,
                    requestData,
                    responseContentType,
                    respDataType
            );
        }
        return null;
    }

    private void rebuildRuleEngine() {
        Rule fieldGuardianRule = new FieldGuardianRule(config);
        Rule geometryGuardianRule = new GeometryGuardianRule(config);
        Rule queryGuardianRule = new QueryGuardianRule(config);
        this.ruleEngine = new RuleEngine(Arrays.asList(fieldGuardianRule, geometryGuardianRule, queryGuardianRule));
        this.responseMasker = new ResponseMasker(config);
        if (behaviorObserver != null) behaviorObserver.close();
        if (behaviorBlockGate != null) behaviorBlockGate.close();
        this.behaviorBlockGate = config.isEnabled() && config.getRiskConfig().enabled() && config.getBehaviorConfig().blockThreshold() > 0
                ? new BehaviorBlockGate(config.getBehaviorConfig(), config.getRiskConfig().profile(), config.isEnforceMode()) : null;
        this.riskAnalyzer = new RiskAnalyzer(config.getRiskConfig(), config.getBehaviorConfig().enabled());
        this.behaviorObserver = config.isEnabled() && config.getRiskConfig().enabled() && config.getBehaviorConfig().enabled()
                ? new BehaviorObserver(config.getBehaviorConfig(), config.getRiskConfig(), config.isEnforceMode(),
                    message -> { if (auditLogger != null) auditLogger.info(message); },
                    message -> { if (auditLogger != null) auditLogger.warning(message); }) : null;
    }

    private void rebuildAuditLogger() {
        AuditLogger arcGisLogger = new ArcGisServerAuditLogger(this.serverLog);
        if (!config.isFileAuditEnabled()) {
            this.auditLogger = arcGisLogger;
            return;
        }

        String directory = config.getFileAuditDirectory();
        if (directory == null || directory.trim().isEmpty()) {
            this.auditLogger = arcGisLogger;
            arcGisLogger.warning("fileAuditEnabled=true but fileAuditDirectory is not configured. File audit disabled.");
            return;
        }

        AuditLogger fileLogger = new FileAuditLogger(
                directory.trim(),
                config.getFileAuditPrefix(),
                config.getFileAuditRetentionDays()
        );
        this.auditLogger = new CompositeAuditLogger(Arrays.asList(arcGisLogger, fileLogger));
    }

    private String operationInputForLog(String operationInput) {
        if (!config.isLogOperationInput()) {
            return "[disabled]";
        }
        return StringUtils.truncate(AuditSanitizer.operationInput(operationInput), config.getMaxLoggedInputLength());
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    // Package-visible SDK boundaries allow REST pipeline tests without a native server.
    IRESTRequestHandler findRestRequestHandlerDelegate() {
        return soiHelper.findRestRequestHandlerDelegate(so);
    }

    BehaviorBlockGate.Result checkBehaviorBlock(RestRequestContext ctx) {
        BehaviorBlockGate gate = behaviorBlockGate;
        return gate == null ? BehaviorBlockGate.Result.allowed() : gate.check(ctx);
    }

    String getCurrentUserNameSafe() {
        try {
            IServerUserInfo userInfo = ServerUtilities.getServerUserInfo();
            if (userInfo == null || userInfo.getName() == null || userInfo.getName().trim().isEmpty()) {
                return "anonymous-or-unknown";
            }
            return userInfo.getName();
        } catch (Exception ex) {
            return "anonymous-or-unknown";
        }
    }
}
