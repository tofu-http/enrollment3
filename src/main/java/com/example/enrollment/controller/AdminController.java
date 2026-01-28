package com.example.enrollment.controller;

import com.example.enrollment.entity.Payment;
import com.example.enrollment.entity.Student;
import com.example.enrollment.repository.PaymentRepository;
import com.example.enrollment.repository.StudentRepository;
import com.example.enrollment.service.FinancialService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/admin") 
public class AdminController {

    private final StudentRepository studentRepository;
    private final PaymentRepository paymentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final FinancialService financialService;

    public AdminController(StudentRepository studentRepository, 
                           PaymentRepository paymentRepository, 
                           JdbcTemplate jdbcTemplate,
                           FinancialService financialService) {
        this.studentRepository = studentRepository;
        this.paymentRepository = paymentRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.financialService = financialService;
    }

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(value = "keyword", required = false) String keyword, Model model) {
        long totalEnrollees = studentRepository.count();
        long pendingCount = studentRepository.findAll().stream()
                .filter(s -> "PENDING".equalsIgnoreCase(s.getApplicantStatus()))
                .count();

        List<Student> searchResults;
        if (keyword != null && !keyword.trim().isEmpty()) {
            searchResults = studentRepository.findByStudentNumberContainingOrLastNameContainingIgnoreCase(keyword, keyword);
        } else {
            searchResults = studentRepository.findAll(); 
        }

        model.addAttribute("totalEnrollees", totalEnrollees);
        model.addAttribute("pendingCount", pendingCount);
        model.addAttribute("students", searchResults);
        model.addAttribute("keyword", keyword);

        return "AdminDashboard"; 
    }
    
    @GetMapping("/walkin-payment")
    public String showWalkinPage(@RequestParam(value = "keyword", required = false) String keyword, Model model) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            // Search by Student Number first
            Student s = studentRepository.findByStudentNumber(keyword.trim());
            
            // If not found by Number, search by Last Name
            if (s == null) {
                List<Student> results = studentRepository.findByLastNameContainingIgnoreCase(keyword.trim());
                if (!results.isEmpty()) {
                    s = results.get(0); // Take the first match
                }
            }

            if (s != null) {
                model.addAttribute("student", s);
                calculateFinancials(s, model);
                model.addAttribute("keyword", keyword);
            } else {
                model.addAttribute("errorMessage", "No student found with that ID or Name.");
            }
        }
        return "admin_walkin_payment";
    }

    private void calculateFinancials(Student s, Model model) {
		// TODO Auto-generated method stub
		
	}

	@PostMapping("/process-walkin")
    public String processWalkInPayment(
            @RequestParam("studentNumber") String studentNumber,
  
            @RequestParam("amount") Double amount,
            @RequestParam(value = "paymentType", defaultValue = "Cash") String paymentType, 
            RedirectAttributes redirectAttributes 
    ) {
        Student student = studentRepository.findByStudentNumber(studentNumber.trim());
        if (student == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Error: Student not found.");
            return "redirect:/admin/walkin-payment";
        }

        Payment payment = new Payment();
        String transactionId = "WLK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(); 
        payment.setTransactionId(transactionId);
        payment.setReferenceNumber(student.getStudentNumber());
        payment.setAmount(amount);
        payment.setPaymentMethod(paymentType + " (Walk-in)"); 
        payment.setPaymentDate(new Date());
        payment.setStatus("COMPLETED");
        paymentRepository.save(payment);
        
        if (!"ENROLLED".equals(student.getApplicantStatus())) {
            student.setApplicantStatus("ENROLLED");
            studentRepository.save(student);
        }

        redirectAttributes.addFlashAttribute("successMessage", "Payment successful for " + student.getLastName());
        redirectAttributes.addFlashAttribute("transactionId", transactionId);
        
        return "redirect:/admin/walkin-payment";
    }
}