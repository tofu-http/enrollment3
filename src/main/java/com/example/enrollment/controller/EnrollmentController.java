package com.example.enrollment.controller;

import com.example.enrollment.entity.Student;
import com.example.enrollment.repository.PaymentRepository;
import com.example.enrollment.repository.StudentRepository;
import com.example.enrollment.service.FinancialService;
import com.example.enrollment.service.SchedulingService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Controller
@SessionAttributes("student")
public class EnrollmentController {

    private final StudentRepository studentRepository;
    private final PaymentRepository paymentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final FinancialService financialService;
    private final SchedulingService schedulingService;

    public EnrollmentController(StudentRepository studentRepository, 
                                PaymentRepository paymentRepository,
                                JdbcTemplate jdbcTemplate,
                                FinancialService financialService,
                                SchedulingService schedulingService) {
        this.studentRepository = studentRepository;
        this.paymentRepository = paymentRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.financialService = financialService;
        this.schedulingService = schedulingService;
    }

    // --- LOGIN & NAVIGATION ---

    @GetMapping("/")
    public String root() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login(@RequestParam(value = "error", required = false) String error,
                        @RequestParam(value = "logout", required = false) String logout,
                        Model model) {
        if (error != null) model.addAttribute("errorMessage", "Invalid username or password.");
        if (logout != null) model.addAttribute("successMessage", "Logged out successfully.");
        return "login";
    }

    @GetMapping("/index")
    public String index(Model model, Principal principal) {
        if (principal != null) {
            Student student = studentRepository.findByStudentNumber(principal.getName());
            if (student != null) {
                model.addAttribute("student", student);
                calculateFinancials(student, model);
            }
        }
        return "index";
    }

    // --- ENLISTMENT & CASHIER TERMINAL ---

    @GetMapping("/admin/cashier")
    public String showCashier(@RequestParam(value = "keyword", required = false) String keyword, Model model) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            Student s = studentRepository.findByStudentNumber(keyword.trim());
            if (s == null) {
                List<Student> list = studentRepository.findByStudentNumberContainingOrLastNameContainingIgnoreCase(keyword, keyword);
                if (!list.isEmpty()) s = list.get(0);
            }
            
            if (s != null) {
                model.addAttribute("student", s);
                calculateFinancials(s, model);
                financialService.populateStudentFinancialData(s, model);
                model.addAttribute("allCourses", jdbcTemplate.queryForList(
                	    "SELECT c.*, cs.max_capacity, " +
                	    "(SELECT COUNT(*) FROM student_enlistments se WHERE se.course_id = c.course_id) as enrolled_count, " +
                	    "(SELECT GROUP_CONCAT(CONCAT(sch.start_time, '-', sch.end_time) SEPARATOR ', ') " +
                	    " FROM class_schedules sch WHERE sch.section_id = cs.section_id) as schedule " +
                	    "FROM courses c " +
                	    "LEFT JOIN class_sections cs ON c.course_id = cs.course_id " +
                	    "WHERE c.active_status = 1"
                	));
            } else {
                model.addAttribute("errorMessage", "Student not found.");
            }
        }
        return "admin_payment";
    }

    @PostMapping("/admin/enlist-subject")
    @Transactional
    public String enlistSubject(@RequestParam Long studentId, 
                                @RequestParam Integer courseId, 
                                @RequestParam(required = false, defaultValue = "false") boolean confirmWaitlist,
                                RedirectAttributes ra) {
        
        Student s = studentRepository.findById(studentId).orElse(null);
        if (s == null) {
            ra.addFlashAttribute("errorMessage", "Student not found.");
            return "redirect:/admin/cashier";
        }
        String studentNum = s.getStudentNumber();

        try {
            // 1. Duplicate Check (Explicit)
            Integer duplicate = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM student_enlistments WHERE student_id = ? AND course_id = ?",
                Integer.class, studentId, courseId);
                
            if (duplicate != null && duplicate > 0) {
                ra.addFlashAttribute("errorMessage", "Error: This subject is already enlisted.");
                return "redirect:/admin/cashier?keyword=" + studentNum;
            }

            // 2. Capacity/Waitlist Check
            Integer sectionId = jdbcTemplate.queryForObject(
                "SELECT section_id FROM class_sections WHERE course_id = ? LIMIT 1", 
                Integer.class, courseId);
                
            if (schedulingService.isSectionFull(sectionId)) {
                if (!confirmWaitlist) {
                    ra.addFlashAttribute("showWaitlistPrompt", true);
                    ra.addFlashAttribute("pendingCourseId", courseId);
                    return "redirect:/admin/cashier?keyword=" + studentNum;
                }
                schedulingService.addToWaitlist(studentId, courseId);
                ra.addFlashAttribute("successMessage", "Added to Waitlist!");
                return "redirect:/admin/cashier?keyword=" + studentNum;
            }

            // 3. Schedule Conflict
            schedulingService.validateStudentScheduleConflict(studentId, sectionId);

            // 4. Perform Insert
            jdbcTemplate.update("INSERT INTO student_enlistments (student_id, course_id) VALUES (?, ?)", 
                               studentId, courseId);
            ra.addFlashAttribute("successMessage", "Subject added successfully!");

        } catch (IllegalStateException e) {
            // Specifically catch the messages you throw
            ra.addFlashAttribute("errorMessage", e.getMessage());
        } catch (Exception e) {
            // Catch unexpected database errors
            ra.addFlashAttribute("errorMessage", "An error occurred: " + e.getLocalizedMessage());
        }
        
        return "redirect:/admin/cashier?keyword=" + studentNum;
    }

    @PostMapping("/admin/remove-subjects-bulk")
    @Transactional
    public String removeSubjectsBulk(
            @RequestParam(value = "enlistmentIds", required = false) List<Long> ids, // Changed to Long
            @RequestParam String studentNumber, 
            RedirectAttributes ra) {

        if (ids == null || ids.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Please select at least one subject to remove.");
            return "redirect:/admin/cashier?keyword=" + studentNumber;
        }

        try {
            for (Long id : ids) {
                // Get courseId before deleting so we can check the waitlist
                Integer cId = jdbcTemplate.queryForObject(
                    "SELECT course_id FROM student_enlistments WHERE enlistment_id = ?", 
                    Integer.class, id);
                
                // Delete the enlistment record
                jdbcTemplate.update("DELETE FROM student_enlistments WHERE enlistment_id = ?", id);
                
                // Trigger waitlist promotion for this specific course
                if (cId != null) {
                    schedulingService.promoteFromWaitlist(cId);
                }
            }
            ra.addFlashAttribute("successMessage", "Successfully removed selected subjects and updated waitlists.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Error during removal: " + e.getMessage());
        }

        return "redirect:/admin/cashier?keyword=" + studentNumber;
    }

    // --- STUDENT SELF-SERVICE ---

    @GetMapping("/account_status")
    public String showAccountStatus(@RequestParam(value = "studentNumber", required = false) String studentNumber, Model model) {
        if (studentNumber != null && !studentNumber.isEmpty()) {
            Student student = studentRepository.findByStudentNumber(studentNumber.trim());
            if (student != null) {
                model.addAttribute("student", student);
                calculateFinancials(student, model); // Calculates units and balance
                
                // ADD THIS LINE to populate the installments table
                financialService.populateStudentFinancialData(student, model); 
                
                return "account_status";
            }
        }
        model.addAttribute("errorMessage", "Student records not found.");
        return "account_status";
    }

    @GetMapping("/status")
    public String showStatus(@RequestParam(value = "searchRef", required = false) String ref, Model model) {
        if (ref != null) {
            Student s = studentRepository.findByStudentNumber(ref.trim());
            if (s != null) {
                model.addAttribute("student", s);
                calculateFinancials(s, model);
            }
        }
        return "enrollment_status";
    }

    // --- HELPER METHOD ---
    private void calculateFinancials(Student student, Model model) {
        // 1. Calculate Units & Tuition
        Integer totalUnits = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(c.credit_units), 0) FROM student_enlistments se JOIN courses c ON se.course_id = c.course_id WHERE se.student_id = ?",
            Integer.class, student.getId());
        
        double tuition = (totalUnits != null ? totalUnits : 0) * 1500.00;
        Double paymentsSum = paymentRepository.getTotalPaymentsByReferenceNumber(student.getStudentNumber());
        double totalPaid = (paymentsSum != null) ? paymentsSum : 0.00;

        model.addAttribute("totalUnits", totalUnits);
        model.addAttribute("tuitionTotal", tuition);
        model.addAttribute("totalOnlinePayments", totalPaid);
        model.addAttribute("outstandingBalance", (tuition + 7431 + 18562) - (totalPaid + 3000));
        
        // 2. Fetch Enlisted Subjects
        List<Map<String, Object>> enlisted = jdbcTemplate.queryForList(
            "SELECT se.enlistment_id, c.course_code, c.course_title, c.credit_units, " +
            "GROUP_CONCAT(CONCAT(sch.start_time, '-', sch.end_time) SEPARATOR ', ') as schedule " +
            "FROM student_enlistments se JOIN courses c ON se.course_id = c.course_id " +
            "LEFT JOIN class_sections cs ON c.course_id = cs.course_id " +
            "LEFT JOIN class_schedules sch ON cs.section_id = sch.section_id " +
            "WHERE se.student_id = ? GROUP BY se.enlistment_id", student.getId());
        model.addAttribute("enlistedSubjects", enlisted);

        // 3. FETCH PAYMENT HISTORY (The New Real-Time Part)
        List<Map<String, Object>> paymentHistory = jdbcTemplate.queryForList(
            "SELECT transaction_id, amount, payment_method, payment_date FROM payments WHERE reference_number = ? ORDER BY payment_date DESC",
            student.getStudentNumber());
        model.addAttribute("paymentHistory", paymentHistory);
    }
}