/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.datacollector.restapi;

import com.streamsets.datacollector.main.BuildInfo;
import com.streamsets.datacollector.util.AuthzRole;
import com.streamsets.datacollector.util.PipelineException;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;

import javax.annotation.security.DenyAll;
import javax.annotation.security.PermitAll;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/v1/system")
@Api(value = "system")
@DenyAll
public class InfoResource {

  private final BuildInfo buildInfo;

  @Inject
  public InfoResource(BuildInfo buildInfo) {
    this.buildInfo = buildInfo;
  }

  @GET
  @Path("/info")
  @ApiOperation(value = "Returns SDC Info", response = Map.class, authorizations = @Authorization(value = "basic"))
  @Produces(MediaType.APPLICATION_JSON)
  @PermitAll
  public Response getBuildInfo() throws PipelineException, IOException {
    return Response.status(Response.Status.OK).entity(buildInfo).build();
  }

  @GET
  @Path("/info/currentUser")
  @ApiOperation(value = "Returns User Info", response = Map.class, authorizations = @Authorization(value = "basic"))
  @Produces(MediaType.APPLICATION_JSON)
  @PermitAll
  public Response getUserInfo(@Context SecurityContext context) throws PipelineException, IOException {
    Map<String, Object> map = new HashMap<>();
    String user;
    List<String> roles = new ArrayList<>();
    Principal principal = context.getUserPrincipal();

    if(principal != null) {
      user = principal.getName();
      if (context.isUserInRole(AuthzRole.GUEST)) {
        roles.add(AuthzRole.GUEST);
      }
      if (context.isUserInRole(AuthzRole.MANAGER)) {
        roles.add(AuthzRole.MANAGER);
      }
      if (context.isUserInRole(AuthzRole.CREATOR)) {
        roles.add(AuthzRole.CREATOR);
      }
      if (context.isUserInRole(AuthzRole.ADMIN)) {
        roles.add(AuthzRole.ADMIN);
      }
    } else {
      //In case of http.authentication=none
      user = "admin";
      roles.add(AuthzRole.ADMIN);
    }

    map.put("user", user);
    map.put("roles", roles);
    return Response.status(Response.Status.OK).entity(map).build();
  }

  @GET
  @Path("/info/serverTime")
  @ApiOperation(value = "Returns Server Time", response = Map.class, authorizations = @Authorization(value = "basic"))
  @Produces(MediaType.APPLICATION_JSON)
  @PermitAll
  public Response getServerTime(@Context SecurityContext context) throws PipelineException, IOException {
    Map<String, Object> map = new HashMap<>();
    map.put("serverTime", System.currentTimeMillis());
    return Response.status(Response.Status.OK).entity(map).build();
  }


}