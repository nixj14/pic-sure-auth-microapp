package edu.harvard.hms.dbmi.avillach.auth.rest;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.dbmi.avillach.util.HttpClientUtil;
import edu.harvard.dbmi.avillach.util.PicsureNaming;
import edu.harvard.dbmi.avillach.util.Utilities;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.auth.AuthorizationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthUtils;
import io.jsonwebtoken.*;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;


@Path("/token")
public class TokenService {

	private Logger logger = LoggerFactory.getLogger(TokenService.class);

	@Inject
	UserRepository userRepo;

	@Inject
	ApplicationRepository applicationRepo;

	@Inject
	AuthorizationService authorizationService;

	@Context
	SecurityContext securityContext;

	@POST
	@RolesAllowed(AuthNaming.AuthRoleNaming.ROLE_TOKEN_INTROSPECTION)
	@Path("/inspect")
	@Consumes("application/json")
	public Response inspectToken(Map<String, Object> inputMap,
			@QueryParam(value = "applicationId") String applicationId){
		logger.info("TokenInspect starting...");
		TokenInspection tokenInspection = _inspectToken(inputMap, applicationId);
		if (tokenInspection.message != null)
			tokenInspection.responseMap.put("message", tokenInspection.message);

		logger.info("Finished token introspection.");
		return PICSUREResponse.success(tokenInspection.responseMap);
	}

	/**
	 * @param inputMap
	 * @param applicationId
	 * @return
	 */
	private TokenInspection _inspectToken(Map<String, Object> inputMap, String applicationId){
		logger.debug("_inspectToken, the incoming token map is: " + inputMap.entrySet()
		.stream()
		.map(entry -> entry.getKey() + " - " + entry.getValue())
		.collect(Collectors.joining(", ")) +" , application id is "
				+ applicationId);

		Application application = null;
		if (applicationId != null)
			application = applicationRepo.getById(UUID.fromString(applicationId));

		TokenInspection tokenInspection = new TokenInspection();

		String token = (String)inputMap.get("token");
		logger.debug("getting token: " + token);
		if (token == null || token.isEmpty()){
			logger.error("Token - "+ token + " is blank");
			tokenInspection.message = "Token not found";
			return tokenInspection;
		}

		// parse the token based on client secret
		// don't need to check if jws is null or not, since parse function has already checked
		Jws<Claims> jws;
		try {
			jws = AuthUtils.parseToken(JAXRSConfiguration.clientSecret, token);
		} catch (NotAuthorizedException ex) {
			tokenInspection.message = ex.getChallenges().toString();
			return tokenInspection;
		}

		String subject = jws.getBody().getSubject();

		// get the user based on subject field in token
		User user;
		try{
			user = userRepo.getByColumn("subject", subject).get(0);
			logger.info("_inspectToken() user with subject - " + subject + " - exists in database");
		} catch (NoResultException e) {
			logger.error("_inspectToken() could not find user with subject " + subject);
			tokenInspection.message = "error: user doesn't exist";
			return tokenInspection;
		}

		//Essentially we want to return jws.getBody() with an additional active: true field
		//only under certain circumstances, the token will return active
		Set<String> privilegeNameSet = null;
		if (user != null
				&& user.getRoles() != null
				&& (privilegeNameSet = user.getPrivilegeNameSet()).contains(AuthNaming.AuthRoleNaming.ROLE_INTROSPECTION_USER)
				&& authorizationService.isAuthorized(inputMap.get("request"), securityContext))
			tokenInspection.responseMap.put("active", true);

		tokenInspection.responseMap.putAll(jws.getBody());

		// add all privileges into response based on application
		// if no application in request, return all privileges for now
		if (application != null) {
			tokenInspection.responseMap.put("privileges", user.getPrivilegeNameSetByApplication(application));
		} else {
			if (privilegeNameSet != null && !privilegeNameSet.isEmpty())
				tokenInspection.responseMap.put("privileges", privilegeNameSet);
		}

		logger.info("_inspectToken() Successfully inspect and return response map: "
				+ tokenInspection.responseMap.entrySet()
				.stream()
				.map(entry -> entry.getKey() + " - " + entry.getValue())
				.collect(Collectors.joining(", ")));
		return tokenInspection;
	}

	/**
	 * inner used token introspection class with active:false included
	 */
	private class TokenInspection {
		Map<String, Object> responseMap = new HashMap<>();
		String message = null;

		public TokenInspection() {
			responseMap.put("active", false);
		}
	}

}
