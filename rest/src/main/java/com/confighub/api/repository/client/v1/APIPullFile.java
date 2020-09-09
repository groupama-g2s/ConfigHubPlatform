/*
 * This file is part of ConfigHub.
 *
 * ConfigHub is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ConfigHub is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ConfigHub.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.confighub.api.repository.client.v1;

import com.confighub.api.repository.client.AClientAccessValidation;
import com.confighub.core.error.ConfigException;
import com.confighub.core.model.ConcurrentContextFilenameResponseCache;
import com.confighub.core.repository.AbsoluteFilePath;
import com.confighub.core.repository.RepoFile;
import com.confighub.core.resolver.Context;
import com.confighub.core.resolver.RepositoryFilesResolver;
import com.confighub.core.store.Store;
import com.confighub.core.utils.FileUtils;
import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;
import java.util.Objects;

@Path("/rawFile")
public class APIPullFile
        extends AClientAccessValidation
{
    private static final Logger log = LogManager.getFormatterLogger(APIPullFile.class);
    private static final ConcurrentContextFilenameResponseCache cache = ConcurrentContextFilenameResponseCache.getInstance();

    @GET
    @Path("/{account}/{repository}")
    public Response get(@PathParam("account") String account,
                        @PathParam("repository") String repositoryName,
                        @HeaderParam("Context") String contextString,
                        @HeaderParam("File") String absPath,
                        @HeaderParam("Repository-Date") String dateString,
                        @HeaderParam("Tag") String tagString,
                        @HeaderParam("Client-Version") String version,
                        @HeaderParam("Application-Name") String appName,
                        @HeaderParam("Security-Profile-Auth") String securityProfiles,
                        @HeaderParam("X-Forwarded-For") String remoteIp)
    {
        Store store = new Store();
        Gson gson = new Gson();

        try
        {
            getRepositoryFromUrl(account, repositoryName, tagString, dateString, store, true);
            checkToken(null, store);
            validatePull(contextString, appName, remoteIp, store, gson, securityProfiles, cache.containsKey(repository, contextString, absPath));
            return getResponse(contextString, absPath, store);
        }
        catch (ConfigException e)
        {
            e.printStackTrace();
            return Response.status(Response.Status.NOT_ACCEPTABLE).tag(e.getMessage()).build();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).tag(e.getMessage()).build();
        }
        finally
        {
            store.close();
        }
    }

    @GET
    public Response get(@HeaderParam("Client-Token") String clientToken,
                        @HeaderParam("Context") String contextString,
                        @HeaderParam("File") String absPath,
                        @HeaderParam("Repository-Date") String dateString,
                        @HeaderParam("Tag") String tagString,
                        @HeaderParam("Client-Version") String version,
                        @HeaderParam("Application-Name") String appName,
                        @HeaderParam("Security-Profile-Auth") String securityProfiles,
                        @HeaderParam("X-Forwarded-For") String remoteIp)
    {
        Store store = new Store();
        Gson gson = new Gson();

        try
        {
            getRepositoryFromToken(clientToken, dateString, tagString, store);
            checkToken(clientToken, store);
            validatePull(contextString, appName, remoteIp, store, gson, securityProfiles, cache.containsKey(repository, contextString, absPath));
            return getResponse(contextString, absPath, store);
        }
        catch (ConfigException e)
        {
            return Response.status(Response.Status.NOT_ACCEPTABLE).tag(e.getMessage()).build();
        }
        catch (Exception e)
        {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).tag(e.getMessage()).build();
        }
        finally
        {
            store.close();
        }
    }

    private Response getResponse(String contextString, String absPath, Store store)
    {
        long start = System.currentTimeMillis();
        Response response = cache.get(repository, contextString, absPath);
        boolean wasFound = Objects.nonNull(response);
        log.info("Cache found [%s] in %d/ms > %s for repository [%s] and path [%s]",
                wasFound,
                System.currentTimeMillis() - start,
                contextString.toLowerCase(),
                repository.getName(),
                absPath);
        if (Objects.isNull(response))
        {
            start = System.currentTimeMillis();
            Context context = resolveContext(contextString, store);
            AbsoluteFilePath absoluteFilePath = store.getAbsFilePath(repository, absPath, date);
            RepoFile file = RepositoryFilesResolver.fullContextResolveForPath(absoluteFilePath, context);
            response = fileResponse(context, file, absoluteFilePath);
            log.info("Response built in %d/ms > %s for repository [%s] and path [%s]",
                    System.currentTimeMillis() - start,
                    context.toString(),
                    repository.getName(),
                    absPath);
        }

        if (repository.isCachingEnabled() && !wasFound)
        {
            start = System.currentTimeMillis();
            cache.putIfAbsent(repository, contextString, absPath, response);
            log.info("Cache stored response in %d/ms > %s for repository [%s] and path [%s]",
                    System.currentTimeMillis() - start,
                    contextString.toLowerCase(),
                    repository.getName(),
                    absPath);
        }

        return response;
    }

    private Response fileResponse(Context context, RepoFile file, AbsoluteFilePath absoluteFilePath)
    {
        if (Objects.isNull(file))
        {
            return Response.noContent().build();
        }
        else
        {
            return Response.ok(FileUtils.resolveFile(context, file, resolved, passwords),
                    absoluteFilePath.getContentType())
                    .build();
        }
    }

}