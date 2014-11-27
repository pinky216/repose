package org.openrepose.core.services.datastore.distributed.impl.distributed.servlet;

import org.openrepose.commons.utils.encoding.EncodingProvider;
import org.openrepose.commons.utils.encoding.UUIDEncodingProvider;
import org.openrepose.commons.utils.io.ObjectSerializer;
import org.openrepose.commons.utils.proxy.RequestProxyService;
import org.openrepose.services.datastore.*;
import org.openrepose.services.datastore.distributed.ClusterConfiguration;
import org.openrepose.services.datastore.distributed.ClusterView;
import org.openrepose.services.datastore.impl.distributed.CacheRequest;
import org.openrepose.services.datastore.impl.distributed.MalformedCacheRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

public class DistributedDatastoreServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(DistributedDatastoreServlet.class);
    private DatastoreAccessControl hostAcl;
    private Datastore localDatastore;
    private DatastoreService datastoreService;
    private static final String DISTRIBUTED_HASH_RING = "distributed/hash-ring";

    /**
     * Current assumptions:
     * TODO: WIPE THIS AWAY WHEN DONE
     * The clusterViewService is started and provided. I assume this guy should be specific to a single repose cluster
     * so it porbably shouldn't be a spring bean.
     * DatastoreService is started and is just the local datastore.
     * RequestProxyService started and is handed in. Wow, this is like 5 classes just to replace httpGet.... madness
     *
     * @param datastore
     * @param requestProxyService
     */
    public DistributedDatastoreServlet(
            DatastoreService datastore,
            ClusterView clusterView, //TODO: this has to be updated externally, to keep the cluster members up to date
            DatastoreAccessControl accessControl,
            RequestProxyService requestProxyService
    ) {
        //Set up everything on instantiation.

        this.datastoreService = datastore;
        localDatastore = datastore.getDefaultDatastore();
        EncodingProvider encodingProvider = UUIDEncodingProvider.getInstance();

        ClusterConfiguration configuration = new ClusterConfiguration(requestProxyService,
                encodingProvider,
                clusterView);

        datastoreService.createDatastore(DISTRIBUTED_HASH_RING, configuration);
        hostAcl = accessControl; //THis ACL isn't thread safe!
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        if (isRequestValid(request, response)) {
            if ("PATCH".equals(request.getMethod())) {
                doPatch(request, response);
            } else {
                super.service(request, response);
            }
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        try {
            CacheRequest cacheGet = CacheRequest.marshallCacheRequest(req);
            final Serializable value = localDatastore.get(cacheGet.getCacheKey());

            if (value != null) {
                resp.getOutputStream().write(ObjectSerializer.instance().writeObject(value));
                resp.setStatus(HttpServletResponse.SC_OK);

            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
        } catch (MalformedCacheRequestException e) {

            LOG.error("Malformed cache request during GET", e);
            switch (e.error) {
                case NO_DD_HOST_KEY:
                    resp.getWriter().write(e.error.message());
                    resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    break;
                case CACHE_KEY_INVALID:
                    resp.getWriter().write(e.error.message());
                    resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    break;
                case OBJECT_TOO_LARGE:
                case TTL_HEADER_NOT_POSITIVE:
                case UNEXPECTED_REMOTE_BEHAVIOR:
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    break;
                default:
                    resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    break;
            }
        } catch (IOException ioe) {
            LOG.error(ioe.getMessage(), ioe);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (CacheRequest.isCacheRequestValid(req)) {
            try {
                final CacheRequest cachePut = CacheRequest.marshallCacheRequestWithPayload(req);
                localDatastore.put(cachePut.getCacheKey(), ObjectSerializer.instance().readObject(cachePut.getPayload()), cachePut.getTtlInSeconds(), TimeUnit.SECONDS);
                resp.setStatus(HttpServletResponse.SC_ACCEPTED);
            } catch (IOException ioe) {
                LOG.error(ioe.getMessage(), ioe);
                throw new DatastoreOperationException("Failed to write payload.", ioe);
            } catch (ClassNotFoundException cnfe) {
                LOG.error(cnfe.getMessage(), cnfe);
                throw new DatastoreOperationException("Failed to deserialize a message. Couldn't find a matching class.", cnfe);
            } catch (MalformedCacheRequestException mcre) {
                handleputMalformedCacheRequestException(mcre, resp);
            }
        } else {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (CacheRequest.isCacheRequestValid(req)) {
            try {
                final CacheRequest cacheDelete = CacheRequest.marshallCacheRequest(req);
                localDatastore.remove(cacheDelete.getCacheKey());
                resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            } catch (MalformedCacheRequestException e) {
                LOG.trace("Malformed cache request on Delete", e);
                switch (e.error) {
                    case NO_DD_HOST_KEY:
                        resp.getWriter().write(e.error.message());
                        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        break;
                    case UNEXPECTED_REMOTE_BEHAVIOR:
                        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        break;
                    default:
                        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        break;
                }
            }
        } else {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    private void doPatch(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (CacheRequest.isCacheRequestValid(request)) {
            try {
                final CacheRequest cachePatch = CacheRequest.marshallCacheRequestWithPayload(request);
                Serializable value = localDatastore.patch(cachePatch.getCacheKey(), (Patch) ObjectSerializer.instance().readObject(cachePatch.getPayload()), cachePatch.getTtlInSeconds(), TimeUnit.SECONDS);
                response.getOutputStream().write(ObjectSerializer.instance().writeObject(value));
                response.setStatus(HttpServletResponse.SC_OK);
            } catch (IOException ioe) {
                LOG.error(ioe.getMessage(), ioe);
                throw new DatastoreOperationException("Failed to write payload.", ioe);
            } catch (ClassNotFoundException cnfe) {
                LOG.error(cnfe.getMessage(), cnfe);
                throw new DatastoreOperationException("Failed to deserialize a message. Couldn't find a matching class.", cnfe);
            } catch (MalformedCacheRequestException mcre) {
                LOG.trace("Handling Malformed Cache Request", mcre);
                handleputMalformedCacheRequestException(mcre, response);
            } catch (ClassCastException e) {
                LOG.trace("Sending ERROR response", e);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            }
        } else {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    public boolean isAllowed(HttpServletRequest request) {
        boolean allowed = hostAcl.shouldAllowAll();

        if (!allowed) {
            try {
                final InetAddress remoteClient = InetAddress.getByName(request.getRemoteHost());

                for (InetAddress allowedAddress : hostAcl.getAllowedHosts()) {
                    if (remoteClient.equals(allowedAddress)) {
                        allowed = true;
                        break;
                    }
                }
            } catch (UnknownHostException uhe) {
                LOG.error("Unknown host exception caught while trying to resolve host: " + request.getRemoteHost() + " Reason: " + uhe.getMessage(), uhe);
            }
        }

        return allowed;
    }

    private boolean isRequestValid(HttpServletRequest req, HttpServletResponse resp) {
        boolean valid = false;
        if (!isAllowed(req)) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        } else if (!CacheRequest.isCacheRequestValid(req)) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
        } else {
            valid = true;
        }

        return valid;
    }

    @Override
    public void destroy() {
        super.destroy();
        LOG.info("Unregistering Datastore: " + DISTRIBUTED_HASH_RING);
        datastoreService.destroyDatastore(DISTRIBUTED_HASH_RING);
    }

    private void handleputMalformedCacheRequestException(MalformedCacheRequestException mcre, HttpServletResponse response) throws IOException {

        LOG.error("Handling Malformed Cache Request", mcre);
        switch (mcre.error) {
            case NO_DD_HOST_KEY:
                response.getWriter().write(mcre.error.message());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                break;
            case OBJECT_TOO_LARGE:
                response.getWriter().write(mcre.error.message());
                response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                break;
            case CACHE_KEY_INVALID:
            case TTL_HEADER_NOT_POSITIVE:
            case UNEXPECTED_REMOTE_BEHAVIOR:
                response.getWriter().write(mcre.error.message());
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                break;
            default:
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                break;
        }

    }
}
