package it.giancagis.arcgis.geovelis;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({FilterVerificationTest.class, RestPipelineTest.class,
        ConfigValidationTest.class, AuditSanitizerTest.class,
        it.giancagis.arcgis.geovelis.risk.RiskAnalyzerTest.class,
        it.giancagis.arcgis.geovelis.behavior.BehaviorEventTest.class})
public class GeoVelisTestSuite { }
