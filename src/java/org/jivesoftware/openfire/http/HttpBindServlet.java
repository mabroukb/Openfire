/**
 * $Revision: $
 * $Date: $
 *
 * Copyright (C) 2005-2008 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution, or a commercial license
 * agreement with Jive.
 */

package org.jivesoftware.openfire.http;

import org.apache.commons.lang.StringEscapeUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.QName;
import org.dom4j.io.XMPPPacketReader;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.net.MXParser;
import org.jivesoftware.util.Log;
import org.jivesoftware.util.JiveGlobals;
import org.mortbay.util.ajax.ContinuationSupport;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Servlet which handles requests to the HTTP binding service. It determines if there is currently
 * an {@link HttpSession} related to the connection or if one needs to be created and then passes it
 * off to the {@link HttpBindManager} for processing of the client request and formulating of the
 * response.
 *
 * @author Alexander Wenckus
 */
public class HttpBindServlet extends HttpServlet {
    private HttpSessionManager sessionManager;
    private HttpBindManager boshManager;

    private static XmlPullParserFactory factory;

    static {
        try {
            factory = XmlPullParserFactory.newInstance(MXParser.class.getName(), null);
        }
        catch (XmlPullParserException e) {
            Log.error("Error creating a parser factory", e);
        }
    }

    private ThreadLocal<XMPPPacketReader> localReader = new ThreadLocal<XMPPPacketReader>();

    public HttpBindServlet() {
    }


    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        super.init(servletConfig);
        boshManager = HttpBindManager.getInstance();
        sessionManager = boshManager.getSessionManager();
        sessionManager.start();
    }


    @Override
    public void destroy() {
        super.destroy();
        sessionManager.stop();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException
    {
        boolean isScriptSyntaxEnabled = boshManager.isScriptSyntaxEnabled();
                
        if(!isScriptSyntaxEnabled) {
            sendLegacyError(response, BoshBindingError.itemNotFound);
            return;
        }

        if (isContinuation(request, response)) {
            return;
        }
        String queryString = request.getQueryString();
        if (queryString == null || "".equals(queryString)) {
            sendLegacyError(response, BoshBindingError.badRequest);
            return;
        }
        queryString = URLDecoder.decode(queryString, "utf-8");

        parseDocument(request, response, new ByteArrayInputStream(queryString.getBytes()));
    }

    private void sendLegacyError(HttpServletResponse response, BoshBindingError error)
            throws IOException
    {
        response.sendError(error.getLegacyErrorCode());
    }


    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (isContinuation(request, response)) {
            return;
        }

        parseDocument(request, response, request.getInputStream());
    }

    private void parseDocument(HttpServletRequest request, HttpServletResponse response,
                               InputStream documentContent)
            throws IOException {

        Document document;
        try {
            document = createDocument(documentContent);
        }
        catch (Exception e) {
            Log.warn("Error parsing user request. [" + request.getRemoteAddr() + "]");
            sendLegacyError(response, BoshBindingError.badRequest);
            return;
        }

        if (JiveGlobals.getBooleanProperty("log.httpbind.enabled", false)) {
            System.out.println(new Date()+": HTTP RECV: " + document.asXML());
        }
        Element node = document.getRootElement();
        if (node == null || !"body".equals(node.getName())) {
            Log.warn("Body missing from request content. [" + request.getRemoteAddr() + "]");
            sendLegacyError(response, BoshBindingError.badRequest);
            return;
        }

        String sid = node.attributeValue("sid");
        // We have a new session
        if (sid == null) {
            createNewSession(request, response, node);
        }
        else {
            handleSessionRequest(sid, request, response, node);
        }
    }

    private boolean isContinuation(HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        HttpSession session = (HttpSession) request.getAttribute("request-session");
        if (session == null) {
            return false;
        }
        synchronized (session) {
            try {
                respond(response, session.getResponse((Long) request.getAttribute("request")),
                        request.getMethod());
            }
            catch (HttpBindException e) {
                sendError(request, response, e.getBindingError(), session);
            }
        }
        return true;
    }

    private void sendError(HttpServletRequest request, HttpServletResponse response,
                           BoshBindingError bindingError, HttpSession session)
            throws IOException
    {
        try {
        	if ((session.getMajorVersion() == 1 && session.getMinorVersion() >= 6) ||
                	session.getMajorVersion() > 1) {
                respond(response, createErrorBody(bindingError.getErrorType().getType(),
                        bindingError.getCondition()), request.getMethod());
            }
            else {
                sendLegacyError(response, bindingError);
            }
        }
        finally {
            if (bindingError.getErrorType() == BoshBindingError.Type.terminate) {
                session.close();
            }
        }
    }

    private String createErrorBody(String type, String condition) {
        Element body = DocumentHelper.createElement("body");
        body.addNamespace("", "http://jabber.org/protocol/httpbind");
        body.addAttribute("type", type);
        body.addAttribute("condition", condition);
        return body.asXML();
    }

    private void handleSessionRequest(String sid, HttpServletRequest request,
                                      HttpServletResponse response, Element rootNode)
            throws IOException
    {
        long rid = getLongAttribue(rootNode.attributeValue("rid"), -1);
        if (rid <= 0) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Body missing RID (Request ID)");
            return;
        }

        HttpSession session = sessionManager.getSession(sid);
        if (session == null) {
            Log.warn("Client provided invalid session: " + sid + ". [" +
                    request.getRemoteAddr() + "]");
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Invalid SID.");
            return;
        }
        synchronized (session) {
            HttpConnection connection;
            try {
                connection = sessionManager.forwardRequest(rid, session,
                        request.isSecure(), rootNode);
            }
            catch (HttpBindException e) {
                sendError(request, response, e.getBindingError(), session);
                return;
            }
            catch (HttpConnectionClosedException nc) {
                Log.error("Error sending packet to client.", nc);
                return;
            }

            String type = rootNode.attributeValue("type");
            String restartStream = rootNode.attributeValue(new QName("restart", rootNode.getNamespaceForPrefix("xmpp")));
            int pauseDuration = getIntAttribue(rootNode.attributeValue("pause"), -1);
            
            if ("terminate".equals(type)) {
                session.close();
                respond(response, createEmptyBody(), request.getMethod());
            }
            else if ("true".equals(restartStream) && rootNode.nodeCount() == 0) {
                try {
					respond(response, createSessionRestartResponse(session), request.getMethod());
				}
				catch (DocumentException e) {
					Log.error("Error sending session restart response to client.", e);
				}
            }
            else if (pauseDuration > 0 && pauseDuration <= session.getMaxPause()) {                
            	session.pause(pauseDuration);
                respond(response, createEmptyBody(), request.getMethod());
                session.setLastResponseEmpty(true);
            }
            else {
                session.resetInactivityTimeout();
                connection.setContinuation(ContinuationSupport.getContinuation(request, connection));
                request.setAttribute("request-session", connection.getSession());
                request.setAttribute("request", connection.getRequestId());
                try {
                    respond(response, session.getResponse(connection.getRequestId()),
                            request.getMethod());
                }
                catch (HttpBindException e) {
                    sendError(request, response, e.getBindingError(), session);
                }
            }
        }
    }

    private String createSessionRestartResponse(HttpSession session) throws DocumentException {
        Element response = DocumentHelper.createElement("body");
        response.addNamespace("", "http://jabber.org/protocol/httpbind");
        response.addNamespace("stream", "http://etherx.jabber.org/streams");

        Element features = response.addElement("stream:features");
        for (Element feature : session.getAvailableStreamFeaturesElements()) {
            features.add(feature);
        }

        return response.asXML();
    }

    private void createNewSession(HttpServletRequest request, HttpServletResponse response,
                                  Element rootNode)
            throws IOException
    {
        long rid = getLongAttribue(rootNode.attributeValue("rid"), -1);
        if (rid <= 0) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Body missing RID (Request ID)");
            return;
        }

        try {
            X509Certificate[] certificates =
                    (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
            HttpConnection connection = new HttpConnection(rid, request.isSecure(), certificates);
            InetAddress address = InetAddress.getByName(request.getRemoteAddr());
            connection.setSession(sessionManager.createSession(address, rootNode, connection));
            respond(response, connection, request.getMethod());
        }
        catch (UnauthorizedException e) {
            // Server wasn't initialized yet.
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Server Not initialized");
        }
        catch (HttpBindException e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

    }

    private void respond(HttpServletResponse response, HttpConnection connection, String method)
            throws IOException
    {
        String content;
        try {
            content = connection.getResponse();
        }
        catch (HttpBindTimeoutException e) {
            content = createEmptyBody();
            connection.getSession().setLastResponseEmpty(true);
        }

        respond(response, content, method);
    }

    private void respond(HttpServletResponse response, String content, String method)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("GET".equals(method) ? "text/javascript" : "text/xml");
        response.setCharacterEncoding("utf-8");

        if ("GET".equals(method)) {
        	// Prevent caching of responses
        	response.addHeader("Cache-Control", "no-store");
        	response.addHeader("Cache-Control", "no-cache");
        	response.addHeader("Pragma", "no-cache");
            content = "_BOSH_(\"" + StringEscapeUtils.escapeJavaScript(content) + "\")";
        }

        if (JiveGlobals.getBooleanProperty("log.httpbind.enabled", false)) {
            System.out.println(new Date()+": HTTP SENT: " + content);
        }
        byte[] byteContent = content.getBytes("utf-8");
        response.setContentLength(byteContent.length);
        response.getOutputStream().write(byteContent);
        response.getOutputStream().close();
    }

    private static String createEmptyBody() {
        Element body = DocumentHelper.createElement("body");
        body.addNamespace("", "http://jabber.org/protocol/httpbind");
        return body.asXML();
    }

    private long getLongAttribue(String value, long defaultValue) {
        if (value == null || "".equals(value)) {
            return defaultValue;
        }
        try {
            return Long.valueOf(value);
        }
        catch (Exception ex) {
            return defaultValue;
        }
    }

    private int getIntAttribue(String value, int defaultValue) {
        if (value == null || "".equals(value)) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(value);
        }
        catch (Exception ex) {
            return defaultValue;
        }
    }

    private XMPPPacketReader getPacketReader() {
        // Reader is associated with a new XMPPPacketReader
        XMPPPacketReader reader = localReader.get();
        if (reader == null) {
            reader = new XMPPPacketReader();
            reader.setXPPFactory(factory);
            localReader.set(reader);
        }
        return reader;
    }

    private Document createDocument(InputStream request) throws
            DocumentException, IOException, XmlPullParserException
    {
        return getPacketReader().read("utf-8", request);
    }
}
