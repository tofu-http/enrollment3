package com.example.enrollment.config;

import com.example.enrollment.entity.User;
import com.example.enrollment.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        // Create ADMIN if not exists
        if (userRepository.findByUsername("admin") == null) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123")); // Password: admin123
            admin.setRole("ADMIN");
            userRepository.save(admin);
            System.out.println("ADMIN ACCOUNT CREATED: admin / admin123");
        }

        // Create STUDENT if not exists
        if (userRepository.findByUsername("student") == null) {
            User student = new User();
            student.setUsername("student");
            student.setPassword(passwordEncoder.encode("student123")); // Password: student123
            student.setRole("STUDENT");
            userRepository.save(student);
            System.out.println("STUDENT ACCOUNT CREATED: student / student123");
        }
    }
}