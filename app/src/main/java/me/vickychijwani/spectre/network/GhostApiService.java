package me.vickychijwani.spectre.network;

import com.google.gson.JsonElement;

import org.json.JSONObject;

import me.vickychijwani.spectre.model.entity.AuthToken;
import me.vickychijwani.spectre.network.entity.AuthReqBody;
import me.vickychijwani.spectre.network.entity.ConfigurationList;
import me.vickychijwani.spectre.network.entity.PostList;
import me.vickychijwani.spectre.network.entity.PostStubList;
import me.vickychijwani.spectre.network.entity.RefreshReqBody;
import me.vickychijwani.spectre.network.entity.RevokeReqBody;
import me.vickychijwani.spectre.network.entity.SettingsList;
import me.vickychijwani.spectre.network.entity.UserList;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;
import retrofit2.http.Url;

interface GhostApiService {

    // auth
    @GET
    Call<String> getLoginPage(@Url String url);

    @POST("authentication/token/")
    Call<AuthToken> getAuthToken(@Body AuthReqBody credentials);

    @POST("authentication/token/")
    Call<AuthToken> refreshAuthToken(@Body RefreshReqBody credentials);

    @POST("authentication/revoke/")
    Call<JsonElement> revokeAuthToken(@Header("Authorization") String authHeader,
                                      @Body RevokeReqBody revoke);

    // users
    @GET("users/me/?include=roles&status=all")
    Call<UserList> getCurrentUser(@Header("Authorization") String authHeader,
                                  @Header("If-None-Match") String etag);

    // posts
    // FIXME (issue #81) only allowing N posts right now to avoid too much data transfer
    @GET("posts/?status=all&staticPages=all&include=tags")
    Call<PostList> getPosts(@Header("Authorization") String authHeader,
                            @Header("If-None-Match") String etag, @Query("limit") int numPosts);

    @GET("posts/{id}/?status=all&include=tags")
    Call<PostList> getPost(@Header("Authorization") String authHeader, @Path("id") int id);

    @POST("posts/?include=tags")
    Call<PostList> createPost(@Header("Authorization") String authHeader,
                              @Body PostStubList posts);

    @PUT("posts/{id}/?include=tags")
    Call<PostList> updatePost(@Header("Authorization") String authHeader,
                              @Path("id") int id, @Body PostStubList posts);

    @DELETE("posts/{id}/")
    Call<String> deletePost(@Header("Authorization") String authHeader, @Path("id") int id);

    // settings / configuration
    @GET("settings/?type=blog")
    Call<SettingsList> getSettings(@Header("Authorization") String authHeader,
                                   @Header("If-None-Match") String etag);

    @GET("configuration/")
    Call<ConfigurationList> getConfiguration(@Header("Authorization") String authHeader,
                                             @Header("If-None-Match") String etag);

    @GET("configuration/about/")
    Call<JSONObject> getVersion(@Header("Authorization") String authHeader);

    // file upload
    @Multipart
    @POST("uploads/")
    void uploadFile(@Header("Authorization") String authHeader,
                    @Part("uploadimage") TypedFile file, Callback<String> cb);

}
