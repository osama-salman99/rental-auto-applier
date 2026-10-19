package eu.roofz.crawler.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import eu.roofz.crawler.model.ApplicantDocument;
import eu.roofz.crawler.model.ApplicantProfile;
import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.DocumentView;
import eu.roofz.crawler.repository.ApplicantProfileRepository;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.service.ApplicantDocumentService;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Dashboard for the applicant documents re-uploaded to every ReletPortal full application. The portal
 * exposes no way to download previously-uploaded files, so the applicant supplies them here once and
 * the pipeline attaches the relevant ones (most recent {@code documentMonthsWindow} of the dated
 * types) at apply time.
 */
@Controller
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    /** Portal attachment types offered in the dashboard. */
    static final List<String> DOC_TYPES =
            List.of("identityDocument", "salarySlip", "employerStatement", "bankStatement");

    /** Human-readable labels for the dropdown (value → label), insertion-ordered. */
    static final java.util.Map<String, String> DOC_TYPE_LABELS =
            new java.util.LinkedHashMap<>() {
                {
                    put("identityDocument", "ID document (front & back)");
                    put("salarySlip", "Payslip");
                    put("employerStatement", "Employer statement");
                    put("bankStatement", "Bank statement");
                }
            };

    /** A single editable full-application field. {@code path} is the dotted location in the profile JSON. */
    public record ProfileField(String path, String label, String type) {
        public String name() {
            return path;
        }
    }

    /**
     * The full-application questionnaire, grouped for display/editing. Only these known leaf fields are
     * ever written back (mutating the stored JSON in place), so the structure the portal expects can't
     * be corrupted by the form.
     */
    static final Map<String, List<ProfileField>> PROFILE_SECTIONS = buildProfileSchema();

    private static Map<String, List<ProfileField>> buildProfileSchema() {
        Map<String, List<ProfileField>> s = new LinkedHashMap<>();
        s.put(
                "Personal details",
                List.of(
                        new ProfileField("person.personalDetails.initials", "Initials", "text"),
                        new ProfileField("person.personalDetails.firstName", "First name", "text"),
                        new ProfileField("person.personalDetails.insertion", "Insertion (tussenvoegsel)", "text"),
                        new ProfileField("person.personalDetails.lastName", "Last name", "text"),
                        new ProfileField("person.personalDetails.email", "Email", "email"),
                        new ProfileField("person.personalDetails.phoneNumber", "Phone", "text"),
                        new ProfileField("person.personalDetails.dateOfBirth", "Date of birth", "date"),
                        new ProfileField("person.personalDetails.gender", "Gender", "text"),
                        new ProfileField("person.personalDetails.livingSituation", "Living situation", "text")));
        s.put(
                "Current address",
                List.of(
                        new ProfileField("person.address.street", "Street", "text"),
                        new ProfileField("person.address.houseNumber", "House number", "number"),
                        new ProfileField("person.address.houseNumberExtension", "Addition", "text"),
                        new ProfileField("person.address.postalCode", "Postal code", "text"),
                        new ProfileField("person.address.city", "City", "text"),
                        new ProfileField("person.address.country", "Country", "text")));
        s.put(
                "ID document",
                List.of(
                        new ProfileField("person.idDocument.idDocumentType", "Document type", "text"),
                        new ProfileField("person.idDocument.idDocumentNumber", "Document number", "text"),
                        new ProfileField("person.idDocument.idIssueDate", "Issue date", "date"),
                        new ProfileField("person.idDocument.idExpirationDate", "Expiry date", "date"),
                        new ProfileField("person.idDocument.idIssueCountry", "Issuing country", "text"),
                        new ProfileField("person.idDocument.cityOfBirth", "City of birth", "text")));
        s.put(
                "Work & income",
                List.of(
                        new ProfileField("person.workSituation.workSituation", "Work situation", "text"),
                        new ProfileField("person.workSituation.workMonthlySalary", "Gross monthly salary (€)", "number"),
                        new ProfileField("person.employment.employerName", "Employer", "text"),
                        new ProfileField("person.employment.workJobTitle", "Job title", "text"),
                        new ProfileField("person.employment.employerContactName", "Employer contact", "text"),
                        new ProfileField("person.employment.employerPhoneNumber", "Employer phone", "text")));
        s.put(
                "Finances",
                List.of(
                        new ProfileField("person.financialSituation.financialSavings", "Savings (€)", "number"),
                        new ProfileField("person.financialSituation.financialCredits", "Outstanding credits (€)", "number"),
                        new ProfileField("person.financialSituation.bankAccount", "IBAN", "text"),
                        new ProfileField("person.financialSituation.otherBankAccount", "Second IBAN (optional)", "text"),
                        new ProfileField("person.financialSituation.annualIncome", "Annual income (€, optional)", "number"),
                        new ProfileField("person.financialSituation.annualIncomeYear", "Year of that annual income", "number")));
        s.put(
                "Household",
                List.of(
                        new ProfileField("partnerSituation", "Applying with a partner", "checkbox"),
                        new ProfileField("currentHousingSituation", "Current housing", "text"),
                        new ProfileField("familyComposition", "Family composition", "text"),
                        new ProfileField("maritalState", "Marital status", "text")));
        return s;
    }

    private final ApplicantDocumentService documentService;
    private final CrawlerConfigRepository configRepository;
    private final ApplicantProfileRepository profileRepository;
    private final ObjectMapper objectMapper;

    public DocumentController(
            ApplicantDocumentService documentService,
            CrawlerConfigRepository configRepository,
            ApplicantProfileRepository profileRepository,
            ObjectMapper objectMapper) {
        this.documentService = documentService;
        this.configRepository = configRepository;
        this.profileRepository = profileRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/documents")
    public String documents(Model model) {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        // Metadata only: this page shows names, months and sizes — never the file bytes.
        List<DocumentView> all = documentService.findAllMeta();
        model.addAttribute("documents", all);
        model.addAttribute("docTypes", DOC_TYPES);
        model.addAttribute("docTypeOptions", DOC_TYPE_LABELS);
        model.addAttribute("docTypeLabels", DOC_TYPE_LABELS);
        model.addAttribute("datedTypes", ApplicantDocumentService.DATED_TYPES);
        model.addAttribute(
                "selected", documentService.selectMetaForApplication(config.documentMonthsWindowOrDefault()));
        model.addAttribute("monthsWindow", config.documentMonthsWindowOrDefault());
        model.addAttribute("autoUpload", config.isPortalAutoUploadDocuments());
        model.addAttribute("autoFinalize", config.isPortalAutoFinalize());

        // Full-application profile, grouped and pre-filled from the stored questionnaire JSON.
        JsonNode profile = readProfileTree();
        Map<String, List<Map<String, String>>> profileSections = new LinkedHashMap<>();
        PROFILE_SECTIONS.forEach(
                (section, fields) -> {
                    List<Map<String, String>> rows = new ArrayList<>();
                    for (ProfileField f : fields) {
                        Map<String, String> row = new LinkedHashMap<>();
                        row.put("name", f.path());
                        row.put("label", f.label());
                        row.put("type", f.type());
                        row.put("value", readPath(profile, f.path()));
                        rows.add(row);
                    }
                    profileSections.put(section, rows);
                });
        model.addAttribute("profileSections", profileSections);
        return "documents";
    }

    /** Persists edits to the full-application profile, overwriting only the known leaf fields. */
    @PostMapping("/documents/profile")
    public String saveProfile(@RequestParam Map<String, String> params, RedirectAttributes ra) {
        try {
            JsonNode tree = readProfileTree();
            ObjectNode root = tree.isObject() ? (ObjectNode) tree : objectMapper.createObjectNode();
            for (List<ProfileField> fields : PROFILE_SECTIONS.values()) {
                for (ProfileField f : fields) {
                    setPath(root, f.path(), coerce(f.type(), params.get(f.path())));
                }
            }
            ApplicantProfile profile = profileRepository.findById(1L).orElseGet(ApplicantProfile::new);
            profile.setId(1L);
            profile.setQuestionnaireJson(objectMapper.writeValueAsString(root));
            profileRepository.save(profile);
            ra.addFlashAttribute("message", "Application details saved.");
        } catch (Exception e) {
            log.error("Failed to save applicant profile", e);
            ra.addFlashAttribute("error", "Couldn't save application details: " + e.getMessage());
        }
        return "redirect:/documents";
    }

    /** Reads the stored profile JSON (or an empty object if none/invalid) so the page never breaks. */
    private JsonNode readProfileTree() {
        try {
            String json = profileRepository.findById(1L).map(ApplicantProfile::getQuestionnaireJson).orElse(null);
            if (StringUtils.hasText(json)) {
                return objectMapper.readTree(json);
            }
        } catch (Exception e) {
            log.warn("Could not parse stored applicant profile: {}", e.getMessage());
        }
        return objectMapper.createObjectNode();
    }

    /** Reads a dotted path to its display string ("" when missing/null). */
    private static String readPath(JsonNode root, String path) {
        JsonNode n = root;
        for (String seg : path.split("\\.")) {
            if (n == null) return "";
            n = n.get(seg);
        }
        if (n == null || n.isNull()) return "";
        return n.asText("");
    }

    /** Coerces a submitted form value to the right JSON node type for the field. */
    private static JsonNode coerce(String type, String raw) {
        if ("checkbox".equals(type)) {
            return BooleanNode.valueOf(raw != null); // an unchecked box submits nothing
        }
        if (!StringUtils.hasText(raw)) {
            return NullNode.getInstance();
        }
        if ("number".equals(type)) {
            try {
                return LongNode.valueOf(Long.parseLong(raw.trim()));
            } catch (NumberFormatException e) {
                return NullNode.getInstance();
            }
        }
        return TextNode.valueOf(raw.trim());
    }

    /** Sets a dotted path, creating intermediate objects as needed. */
    private static void setPath(ObjectNode root, String path, JsonNode value) {
        String[] segs = path.split("\\.");
        ObjectNode node = root;
        for (int i = 0; i < segs.length - 1; i++) {
            JsonNode next = node.get(segs[i]);
            ObjectNode child;
            if (next instanceof ObjectNode on) {
                child = on;
            } else {
                child = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                node.set(segs[i], child);
            }
            node = child;
        }
        node.set(segs[segs.length - 1], value);
    }

    @PostMapping("/documents/upload")
    public String upload(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "docType", required = false) String docType,
            @RequestParam(value = "documentMonth", required = false) String documentMonth,
            jakarta.servlet.http.HttpServletRequest request,
            RedirectAttributes ra)
            throws IOException {
        // Diagnostic: log exactly what the request carried so a "no file" report can be pinpointed.
        try {
            String parts =
                    (request instanceof org.springframework.web.multipart.MultipartHttpServletRequest mreq)
                            ? String.valueOf(mreq.getMultiFileMap().keySet())
                            : "(not a MultipartHttpServletRequest)";
            log.info(
                    "upload received: contentType={} docType={} filePresent={} fileName={} size={} parts={}",
                    request.getContentType(),
                    docType,
                    file != null,
                    file == null ? null : file.getOriginalFilename(),
                    file == null ? -1 : file.getSize(),
                    parts);
        } catch (Exception diag) {
            log.warn("upload diagnostic logging failed: {}", diag.getMessage());
        }
        if (file == null || file.isEmpty()) {
            ra.addFlashAttribute("error", "No file received — pick a file, then tap Upload.");
            return "redirect:/documents";
        }
        if (!DOC_TYPES.contains(docType)) {
            ra.addFlashAttribute("error", "Unknown document type: " + docType);
            return "redirect:/documents";
        }
        LocalDate date = parseMonth(documentMonth);
        if (ApplicantDocumentService.DATED_TYPES.contains(docType) && date == null) {
            ra.addFlashAttribute(
                    "error", "A month (YYYY-MM) is required for " + docType + " so the latest ones can be chosen.");
            return "redirect:/documents";
        }
        String name = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "document";
        String contentType =
                StringUtils.hasText(file.getContentType()) ? file.getContentType() : "application/octet-stream";
        documentService.save(new ApplicantDocument(docType, name, contentType, file.getBytes(), date));
        log.info("Uploaded document '{}' type={} month={} ({} bytes)", name, docType, date, file.getSize());
        ra.addFlashAttribute(
                "message",
                "Uploaded \"" + name + "\" as " + DOC_TYPE_LABELS.getOrDefault(docType, docType)
                        + (date != null ? " (" + date.getYear() + "-" + String.format("%02d", date.getMonthValue()) + ")" : "")
                        + ".");
        return "redirect:/documents";
    }

    @PostMapping("/documents/delete/{id}")
    public String delete(@PathVariable long id, RedirectAttributes ra) {
        documentService.delete(id);
        ra.addFlashAttribute("message", "Deleted document " + id + ".");
        return "redirect:/documents";
    }

    /**
     * A submit that didn't arrive as a proper multipart body (e.g. no file attached on mobile) would
     * otherwise 500. Turn it into a friendly redirect so the user just sees "pick a file and retry".
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(
            org.springframework.web.multipart.MultipartException.class)
    public String onMultipartError(RedirectAttributes ra) {
        ra.addFlashAttribute("error", "Upload didn't include a file — pick a file, then tap Upload.");
        return "redirect:/documents";
    }

    /** Parses {@code YYYY-MM} to the first of that month; returns null when blank/invalid. */
    private static LocalDate parseMonth(String yyyyMm) {
        if (!StringUtils.hasText(yyyyMm)) {
            return null;
        }
        try {
            String v = yyyyMm.trim();
            return LocalDate.parse(v.length() == 7 ? v + "-01" : v);
        } catch (Exception e) {
            return null;
        }
    }
}
