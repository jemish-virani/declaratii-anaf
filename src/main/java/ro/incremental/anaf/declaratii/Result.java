package ro.incremental.anaf.declaratii;

import com.google.common.base.MoreObjects;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import dec.Validation;
import org.json.JSONObject;
import pdf.PdfCreation;

import java.io.*;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Alex Proca <alex.proca@gmail.com> on 14/04/16.
 */
public class Result {
    public final String message;
    public final String decName;
    public final File pdfFile;
    public final File xmlFile;
    public final File errFile;
    public final int resultCode;

    private String hashCode = null;

    private static final int PERIOADA_RAPORTARE_ERONATA = -4;
    private static final int FISIER_VALID = 0;
    private static final int TIP_DECLARATIE_NECUNOSCUT = -8;

    public static final int UNKNOWN_ERROR = -9;
    private static final Map<String, Class<PdfCreation>> creators = new HashMap<>();
    private static final Map<String, Class<Validation>> validators = new HashMap<>();
    private static final Cache<String, Result> fileCache;

    // Lock to synchronize validation calls - ANAF validator JARs have static state
    // that gets corrupted when multiple validations run concurrently
    // Using ReentrantLock with 2-minute timeout instead of synchronized block
    private static final ReentrantLock VALIDATION_LOCK = new ReentrantLock(true); // fair=true for FIFO ordering

    static {
        InputStream packages = Thread.currentThread().getContextClassLoader().getResourceAsStream("packages.txt");
        BufferedReader reader = new BufferedReader(new InputStreamReader(packages));


        String line;

        try {
            while ((line = reader.readLine()) != null) {
                try {

                    creators.put(line, (Class<PdfCreation>) Class.forName(line + ".PdfCreator"));
                    validators.put(line, (Class<Validation>) Class.forName(line + "validator.Validator"));

                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        fileCache = CacheBuilder.<String, Result>newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .removalListener((RemovalListener<String, Result>) notification -> notification.getValue().cleanup())
                .build();
    }

    public Result(String message, int resultCode) {
        this.message = message;
        this.resultCode = resultCode;
        this.pdfFile = null;
        this.xmlFile = null;
        this.errFile = null;
        this.decName = "";
    }

    public Result(String message, int resultCode, File pdfFile, File xmlFile, File errFile, String decName) {
        this.message = message;
        this.resultCode = resultCode;
        this.pdfFile = pdfFile;
        this.xmlFile = xmlFile;
        this.errFile = errFile;
        this.decName = decName;
    }

    public void cleanup() {
        if (pdfFile != null) {
            pdfFile.delete();
        }
        if (xmlFile != null) {
            xmlFile.delete();
        }
        if (errFile != null) {
            errFile.delete();
        }
    }

    public String getHashCode() {
        if (pdfFile == null) {
            return null;
        }
        if (hashCode == null) {
            hashCode = Hashing.murmur3_32().hashString(pdfFile.getAbsolutePath(), Charset.forName("UTF-8")).toString();
        }
        return hashCode;
    }

    public String toJSON() {
        JSONObject ret = new JSONObject();

        ret.put("message", this.message);
        ret.put("fileId", this.hashCode);
        ret.put("decName", this.decName);
        ret.put("resultCode", this.resultCode);

        return ret.toString();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper("Result")
                .add("Message", message)
                .add("FileId", hashCode)
                .add("DecName", decName)
                .add("ResultCode", resultCode)
                .toString();
    }

    public static Result generateFromXMLString(String xml, String declName) {
        try {
            File tempDir = Files.createTempDir();
            File xmlFile = new File(tempDir, declName + ".xml");
            Files.newWriter(xmlFile, Charset.forName("UTF-8")).append(xml).close();

            System.out.println("filePath: " + xmlFile.getAbsolutePath());
            System.out.println("decName: " + declName);

            return generatePdfFromXMLFile(xmlFile.getAbsolutePath(), declName);

        } catch (Throwable e) {
            e.printStackTrace();
            return new Result(e.getMessage(), UNKNOWN_ERROR);
        }
    }

    public static void cacheResult(Result result) {

        fileCache.put(result.getHashCode(), result);
    }

    public static Result getFromCache(String id) {

        return fileCache.getIfPresent(id);
    }

    public static Result generatePdfFromXMLFile(String xmlFilePath, String declName) {
        boolean lockAcquired = false;
        try {
            // Wait up to 2 minutes to acquire lock, then give up
            lockAcquired = VALIDATION_LOCK.tryLock(2, TimeUnit.MINUTES);
            if (!lockAcquired) {
                return new Result("Server is busy processing other documents. Please try again in a few minutes.", UNKNOWN_ERROR);
            }

            Validation validator = validators.get(declName).newInstance();
            PdfCreation pdfCreation = creators.get(declName).newInstance();

            String finalMessage = "";
            String newLine = System.lineSeparator();

            String errFilePath = xmlFilePath + ".err.txt";
            String pdfFilePath = xmlFilePath + ".pdf";

            int returnCode = validator.parseDocument(xmlFilePath, errFilePath);

            switch (returnCode) {
                case PERIOADA_RAPORTARE_ERONATA:
                    finalMessage += "Perioada raportare eronata \"" + declName + "\" " + newLine;
                    break;
                case TIP_DECLARATIE_NECUNOSCUT:
                    finalMessage += "Tip declaratie necunoscut \"" + declName + "\" " + newLine;
                    break;
                case FISIER_VALID:
                    finalMessage += "Validare fara erori " + newLine;
                    break;
                default:

                    File errFile = new File(errFilePath);
                    StringBuilder errorBuffer = new StringBuilder();
                    if (errFile.exists()) {
                        BufferedReader reader = Files.newReader(errFile, Charset.forName("UTF-8"));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            errorBuffer.append(line).append(newLine);
                        }
                    }

                    if (returnCode > FISIER_VALID) {
                        finalMessage += "Atentionari la validare fisier " + newLine + errorBuffer;
                        break;
                    }

                    if (returnCode > PERIOADA_RAPORTARE_ERONATA) {
                        finalMessage += "Erori la validare fisier " + newLine + errorBuffer;
                        break;
                    }

                    finalMessage += "Erori la validare fisier; cod eroare=" + returnCode + newLine;
                    break;
            }

            if (returnCode >= FISIER_VALID) {
                pdfCreation.createPdf(validator.getInfo(), pdfFilePath, xmlFilePath, "");

                Result result = new Result(finalMessage, returnCode, new File(pdfFilePath), new File(xmlFilePath), new File(errFilePath), declName);

                return result;
            }

            return new Result(finalMessage, returnCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("Validation was interrupted", UNKNOWN_ERROR);
        } catch (Throwable e) {
            e.printStackTrace();
            return new Result(e.getMessage(), UNKNOWN_ERROR);
        } finally {
            if (lockAcquired) {
                VALIDATION_LOCK.unlock();
            }
        }
    }
}
