package it.giancagis.arcgis.geovelis.rules;

import it.giancagis.arcgis.geovelis.rest.RestRequestContext;

/**
 * A pre-processing rule for REST requests.
 */
public interface Rule {
    RuleResult before(RestRequestContext ctx);
}
