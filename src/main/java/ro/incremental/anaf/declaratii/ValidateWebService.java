package ro.incremental.anaf.declaratii;

/**
 * Created by Alex Proca <alex.proca@gmail.com> on 18/03/16.
 */

import org.json.JSONObject;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import org.glassfish.jersey.media.multipart.*;
import java.io.*;
import com.google.common.io.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Path("/")
public class ValidateWebService {

    private static final String indexHtml;
    private static final String javascript;

    static {
        indexHtml = cacheResource("index.html");
        javascript = cacheResource("javascript.js");
    }

    @GET
    @Path("/")
    @Produces(MediaType.TEXT_HTML)
    public Response index() {

        return Response.ok(indexHtml).build();
    }

    @GET
    @Path("/javascript.js")
    @Produces(MediaType.TEXT_HTML)
    public Response javascript() {

        return Response.ok(javascript).build();
    }

    @GET
    @Path("/available")
    @Produces(MediaType.TEXT_PLAIN)
    public String available() {
        return "yes";
    }

    @GET
    @Path("/download/{id}")
    @Produces("application/pdf")
    public Response download(@PathParam("id") String id) {

        Result result = Result.getFromCache(id);

        if (result == null) {
            return Response.noContent().build();
        }

        Response.ResponseBuilder response = Response.ok(result.pdfFile);
        response.header("Content-Disposition", "attachment; filename=\"" + result.decName + ".pdf\"");

        return response.build();
    }

    @POST
    @Path("/validate")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response available(JSONObject input) {

        Result result = Result.generateFromXMLString(json2Xml(input), getDeclName(input));

        if (result.getHashCode() != null) {
            Result.cacheResult(result);
        }

        return Response.ok(result.toJSON(), MediaType.APPLICATION_JSON).build();
    }

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadXmlFile(
        @FormDataParam("file") InputStream uploadedInputStream,
        @FormDataParam("file") FormDataContentDisposition fileDetail,
        @FormDataParam("decName") String decName) {

        System.out.println("uploadedInputStream: " + uploadedInputStream);
        System.out.println("fileDetail: " + fileDetail);
        System.out.println("decName: " + decName);


        if (uploadedInputStream == null || fileDetail == null || decName == null || decName.isEmpty()) {
            // Return JSON error response with 400 status
            String errorMessage = "No file uploaded or missing declaration name";
            Result result = new Result(errorMessage, -9);
            return Response.ok(result.toJSON(), MediaType.APPLICATION_JSON).build();

        }

        try {
            // Create a temporary directory
            File tempDir = Files.createTempDir();
            File storedFile = null;
            String fileName = fileDetail.getFileName().toLowerCase();

            if (fileName.endsWith(".zip")) {
                // Handle ZIP upload
                try (ZipInputStream zis = new ZipInputStream(uploadedInputStream)) {
                    ZipEntry entry = zis.getNextEntry();
                    if (entry != null && !entry.isDirectory()) {
                        // Create a new file in the temp directory with the uploaded file name
                        storedFile = new File(tempDir, entry.getName());
                        try (FileOutputStream out = new FileOutputStream(storedFile)) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = zis.read(buffer)) != -1) {
                                out.write(buffer, 0, bytesRead);
                            }
                        }
                        zis.closeEntry();
                        System.out.println("Extracted XML from ZIP: " + storedFile.getAbsolutePath());
                    } else {
                        throw new IOException("Zip file does not contain a valid XML file");
                    }
                }
            }else {
                // Create a new file in the temp directory with the uploaded file name
                storedFile = new File(tempDir, fileDetail.getFileName());
                try (FileOutputStream out = new FileOutputStream(storedFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = uploadedInputStream.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
                System.out.println("Stored XML file: " + storedFile.getAbsolutePath());
            }

            String lowerCaseDecName = decName.toLowerCase();
            Result result = Result.generatePdfFromXMLFile(storedFile.getAbsolutePath(), lowerCaseDecName);

            if (result.getHashCode() != null) {
                Result.cacheResult(result);
            }


            // Return JSON response with result data which contains data to download a pdf
            return Response.ok(result.toJSON(), MediaType.APPLICATION_JSON).build();


        } catch (IOException e) {
            e.printStackTrace();
            Result result = new Result(e.getMessage(), -9);
            return Response.ok(result.toJSON(), MediaType.APPLICATION_JSON).build();
        }
    }

    private static String json2Xml(JSONObject input) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" + org.json.JSONML.toString(input);
    }

    private static String getDeclName(JSONObject input) {
        return input.getString("tagName").replace("declaratie", "d");
    }

    private static String cacheResource(String resource) {
        String line;
        InputStream indexHtmlStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        BufferedReader htmlReader = new BufferedReader(new InputStreamReader(indexHtmlStream));

        StringBuilder result = new StringBuilder();

        try {
            while ((line = htmlReader.readLine()) != null) {
                result.append(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return result.toString();
    }

}
