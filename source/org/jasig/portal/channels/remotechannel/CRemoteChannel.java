/**
 * Copyright � 2002 The JA-SIG Collaborative.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. Redistributions of any form whatsoever must retain the following
 *    acknowledgment:
 *    "This product includes software developed by the JA-SIG Collaborative
 *    (http://www.jasig.org/)."
 *
 * THIS SOFTWARE IS PROVIDED BY THE JA-SIG COLLABORATIVE "AS IS" AND ANY
 * EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE JA-SIG COLLABORATIVE OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package org.jasig.portal.channels.remotechannel;

import org.jasig.portal.channels.BaseChannel;
import org.jasig.portal.ICacheable;
import org.jasig.portal.ChannelCacheKey;
import org.jasig.portal.PortalException;
import org.jasig.portal.ResourceMissingException;
import org.jasig.portal.ChannelStaticData;
import org.jasig.portal.PortalEvent;
import org.jasig.portal.BrowserInfo;
import org.jasig.portal.utils.XSLT;
import org.jasig.portal.utils.ResourceLoader;
import org.jasig.portal.security.*;
import org.jasig.portal.security.provider.*;
import org.jasig.portal.channels.remotechannel.*;
import org.w3c.dom.Element;
import org.xml.sax.ContentHandler;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.Map;
import java.rmi.RemoteException;
import javax.servlet.http.Cookie;
import javax.xml.rpc.ServiceException;

/**
 * <p>A proxy channel for remote channels exposed by the uPortal
 * Web Services layer</p>
 * @author Ken Weiner, kweiner@interactivebusiness.com
 * @version $Revision$
 */
public class CRemoteChannel extends BaseChannel implements ICacheable {

  protected RemoteChannel rc = null;
  protected String instanceId = null;
  protected static final String SSL_LOCATION = "CRemoteChannel.ssl";
  protected String xslUriForKey = null;


  public void setStaticData(ChannelStaticData sd) throws PortalException {
    super.setStaticData(sd);
    String endpoint = staticData.getParameter("endpoint");
    String fname = staticData.getParameter("fname");
    RemoteChannelService rcs = new RemoteChannelServiceLocator();

    // Obtain a stub that talks to the remote channel service
    try {
      rc = rcs.getRemoteChannel(new URL(endpoint));
    } catch (MalformedURLException mue) {
      throw new ResourceMissingException(endpoint, "Remote channel service endpoint", mue.getMessage());
    } catch (ServiceException se) {
      throw new PortalException(se);
    }

    // Authenticate and instantiate an instance of the remote channel
    try {
      authenticate();
      instanceId = rc.instantiateChannel(fname);
    } catch (RemoteException re) {
      throw new PortalException(re);
    }
  }

  public void receiveEvent(PortalEvent ev) {
    try {
      if (ev.getEventNumber() == PortalEvent.SESSION_DONE) {
        rc.logout();
      } else if (ev.getEventNumber() == PortalEvent.UNSUBSCRIBE) {
        rc.freeChannel(instanceId);
      }
    } catch (RemoteException re) {
      // Do nothing
    }
  }

  public void renderXML(ContentHandler out) throws PortalException {
    // Set up arguments to renderChannel()
    BrowserInfo bi = runtimeData.getBrowserInfo();
    Map headers = bi.getHeaders();
    Cookie[] cookies = bi.getCookies();
    Map params = runtimeData.getParameters();
    String baseActionURL = runtimeData.getBaseActionURL();

    // Obtain the channel content
    Element channelE = null;
    try {
      channelE = rc.renderChannel(instanceId, headers, cookies, params, baseActionURL);
    } catch (RemoteException re) {
      throw new PortalException(re);
    }

    XSLT xslt = new XSLT(this);
    xslt.setXML(channelE);
    xslt.setXSL(SSL_LOCATION, bi);
    xslt.setTarget(out);
    xslt.transform();
  }

  // Helper methods

  /**
   * If credentials are cached by the security provider,
   * use them to authenticate
   * @throws RemoteException
   */
  protected void authenticate() throws RemoteException {
    String username = (String)staticData.getPerson().getAttribute("username");
    String password = null;
    ISecurityContext ic = staticData.getPerson().getSecurityContext();
    IOpaqueCredentials oc = ic.getOpaqueCredentials();
    if (oc instanceof NotSoOpaqueCredentials) {
      NotSoOpaqueCredentials nsoc = (NotSoOpaqueCredentials)oc;
      password = nsoc.getCredentials();
    }

    // If still no password, loop through subcontexts to find cached credentials
    if (password == null) {
      java.util.Enumeration en = ic.getSubContexts();
      while (en.hasMoreElements()) {
        ISecurityContext sctx = (ISecurityContext)en.nextElement();
        IOpaqueCredentials soc = sctx.getOpaqueCredentials();
        if (soc instanceof NotSoOpaqueCredentials) {
          NotSoOpaqueCredentials nsoc = (NotSoOpaqueCredentials)soc;
          password = nsoc.getCredentials();
        }
      }
    }

    if (username != null && password != null)
      rc.authenticate("demo", "demo");
  }

  // ICacheable methods

  /**
   * Generates a channel cache key.  The key scope is set to be system-wide
   * when the channel is anonymously accessed, otherwise it is set to be
   * instance-wide.  The caching implementation here is simple and may not
   * handle all cases.  Please improve these ICacheable methods as necessary.
   */
  public ChannelCacheKey generateKey() {
    ChannelCacheKey cck = new ChannelCacheKey();
    StringBuffer sbKey = new StringBuffer(1024);

    // Anonymously accessed pages can be cached system-wide
    if(staticData.getPerson().isGuest()) {
      cck.setKeyScope(ChannelCacheKey.SYSTEM_KEY_SCOPE);
      sbKey.append("SYSTEM_");
    } else {
      cck.setKeyScope(ChannelCacheKey.INSTANCE_KEY_SCOPE);
    }

    if (xslUriForKey == null) {
      try {
        String sslUri = ResourceLoader.getResourceAsURLString(this.getClass(), SSL_LOCATION);
        xslUriForKey = XSLT.getStylesheetURI(sslUri, runtimeData.getBrowserInfo());
      } catch (PortalException pe) {
        xslUriForKey = "Not attainable!";
      }
    }
    sbKey.append("xslUri: ").append(xslUriForKey);
    cck.setKey(sbKey.toString());
    return cck;
  }

  /**
   * Return <code>true</code> when no runtime parameters are sent to the
   * channel, otherwise <code>false</code>
   */
  public boolean isCacheValid(Object validity) {
    return runtimeData.size() == 0;
  }
}
