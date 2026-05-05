/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.navimow.internal;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.navimow.internal.api.NavimowApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link NavimowAuthServlet} handles the OAuth2 authorization flow for Navimow bridges.
 *
 * <p>
 * Visit {@code http://openhab:8080/navimow} to see the authorization page. Click the
 * <em>Authorize</em> link to be redirected to the Navimow login page. After login, you will be
 * redirected back here and the access token will be saved automatically.
 * </p>
 *
 * @author Jimmy Tanagra - Initial contribution
 */
@NonNullByDefault
public class NavimowAuthServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String CONTENT_TYPE = "text/html;charset=UTF-8";

    private final Logger logger = LoggerFactory.getLogger(NavimowAuthServlet.class);
    private final NavimowAuthService authService;

    public NavimowAuthServlet(NavimowAuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doGet(@Nullable HttpServletRequest req, @Nullable HttpServletResponse resp) throws IOException {
        if (req == null || resp == null) {
            return;
        }

        logger.debug("Navimow auth servlet GET: {}", req.getRequestURI());

        String servletBaseURL = req.getRequestURL().toString();
        String queryString = req.getQueryString();

        String statusMessage = "";

        if (queryString != null && !queryString.isEmpty()) {
            String code = req.getParameter("code");
            String state = req.getParameter("state");
            String error = req.getParameter("error");

            if (error != null && !error.isEmpty()) {
                logger.warn("OAuth2 authorization failed with error: {}", error);
                statusMessage = "<p class=\"msg-error\">Authorization failed: " + escapeHtml(error) + "</p>";
            } else if (code != null && state != null) {
                try {
                    String bridgeLabel = authService.authorize(servletBaseURL, state, code);
                    statusMessage = "<p class=\"msg-ok\">Successfully authorized bridge: " + escapeHtml(bridgeLabel)
                            + "</p>";
                } catch (NavimowApiException e) {
                    logger.warn("OAuth2 code exchange failed: {}", e.getMessage());
                    String errMsg = Objects.requireNonNullElse(e.getMessage(), "Unknown error");
                    statusMessage = "<p class=\"msg-error\">Authorization failed: " + escapeHtml(errMsg) + "</p>";
                }
            }
        }

        resp.setContentType(CONTENT_TYPE);
        PrintWriter writer = resp.getWriter();
        writer.println(buildPage(servletBaseURL, statusMessage));
        writer.close();
    }

    private String buildPage(String servletBaseURL, String statusMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n");
        sb.append("<meta charset=\"utf-8\">\n");
        sb.append("<title>Navimow Authorization</title>\n");
        sb.append("<style>\n");
        sb.append("body{font-family:sans-serif;max-width:600px;margin:2em auto;padding:0 1em;}\n");
        sb.append(".bridge{border:1px solid #ccc;border-radius:4px;padding:1em;margin:1em 0;}\n");
        sb.append(".authorized{border-color:#2e7d32;background:#f1f8e9;}\n");
        sb.append(".not-authorized{border-color:#c62828;background:#fff8f8;}\n");
        sb.append(".msg-ok{color:#2e7d32;font-weight:bold;}\n");
        sb.append(".msg-error{color:#c62828;font-weight:bold;}\n");
        sb.append("a.btn{display:inline-block;padding:.4em 1em;background:#1565c0;color:#fff;");
        sb.append("text-decoration:none;border-radius:3px;margin-top:.5em;}\n");
        sb.append("</style>\n</head>\n<body>\n");
        sb.append("<h1>Navimow Authorization</h1>\n");
        sb.append(statusMessage);

        List<NavimowBridgeHandler> bridgeHandlers = authService.getBridgeHandlers();
        if (bridgeHandlers.isEmpty()) {
            sb.append(
                    "<p>No Navimow bridges configured. Add a <em>Navimow Account</em> bridge in openHAB first.</p>\n");
        } else {
            for (NavimowBridgeHandler handler : bridgeHandlers) {
                boolean authorized = handler.isAuthorized();
                sb.append("<div class=\"bridge ").append(authorized ? "authorized" : "not-authorized").append("\">\n");
                sb.append("<h2>").append(escapeHtml(handler.getLabel())).append("</h2>\n");
                sb.append("<p>Status: <strong>").append(authorized ? "Authorized" : "Not authorized")
                        .append("</strong></p>\n");
                String authorizeUrl = handler.formatAuthorizationUrl(servletBaseURL);
                if (!authorizeUrl.isEmpty()) {
                    sb.append("<a class=\"btn\" href=\"").append(escapeHtml(authorizeUrl)).append("\">");
                    sb.append(authorized ? "Re-authorize" : "Authorize").append("</a>\n");
                }
                sb.append("</div>\n");
            }
        }

        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
