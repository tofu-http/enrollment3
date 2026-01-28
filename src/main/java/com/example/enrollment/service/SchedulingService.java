package com.example.enrollment.service;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.enrollment.entity.ClassSchedule;
import com.example.enrollment.repository.ClassScheduleRepository;
import jakarta.transaction.Transactional;

@Service
public class SchedulingService {

    @Autowired
    private ClassScheduleRepository scheduleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Checks if the section has reached its maximum student capacity.
     */
    public boolean isSectionFull(Integer sectionId) {
        Map<String, Object> data = jdbcTemplate.queryForMap(
            "SELECT cs.max_capacity, " +
            "(SELECT COUNT(*) FROM student_enlistments se WHERE se.course_id = cs.course_id) as current_count " +
            "FROM class_sections cs WHERE cs.section_id = ?", sectionId);

        int max = (int) data.get("max_capacity");
        // Handling the count which can be returned as Long or Integer depending on DB driver
        long current = ((Number) data.get("current_count")).longValue();
        
        return current >= max;
    }

    /**
     * Adds a student to the waitlist table.
     */
    @Transactional
    public void addToWaitlist(Long studentId, Integer courseId) {
        jdbcTemplate.update(
            "INSERT INTO student_waitlist (student_id, course_id, status) VALUES (?, ?, 'WAITING')", 
            studentId, courseId);
    }

    /**
     * Detects schedule overlaps between a new section and a student's current load.
     */
    public void validateStudentScheduleConflict(Long studentId, Integer sectionIdToCheck) {
        // Get the schedules for the new section
        List<ClassSchedule> newSchedules = scheduleRepository.findBySection_SectionId(sectionIdToCheck);

        // Get current schedules for the student
        List<Map<String, Object>> currentSchedules = jdbcTemplate.queryForList(
            "SELECT sch.start_time, sch.end_time, sch.day_of_week, c.course_title " +
            "FROM student_enlistments se " +
            "JOIN courses c ON se.course_id = c.course_id " +
            "JOIN class_sections cs ON c.course_id = cs.course_id " +
            "JOIN class_schedules sch ON cs.section_id = sch.section_id " +
            "WHERE se.student_id = ?", studentId);

        for (ClassSchedule newSched : newSchedules) {
            for (Map<String, Object> row : currentSchedules) {
                Integer existingDay = (Integer) row.get("day_of_week");
                
                if (existingDay.equals(newSched.getDayOfWeek())) {
                    LocalTime exStart = ((java.sql.Time) row.get("start_time")).toLocalTime();
                    LocalTime exEnd = ((java.sql.Time) row.get("end_time")).toLocalTime();

                    // Standard Overlap Formula: (StartA < EndB) AND (StartB < EndA)
                    if (newSched.getStartTime().isBefore(exEnd) && exStart.isBefore(newSched.getEndTime())) {
                        throw new IllegalStateException("Schedule Conflict with " + row.get("course_title") + 
                            " on day " + existingDay);
                    }
                }
            }
        }
    }

    /**
     * Automatically promotes the next student from the waitlist if a spot opens up.
     */
    @Transactional
    public void promoteFromWaitlist(Integer courseId) {
        // 1. Get the primary section for this course
        List<Integer> sectionIds = jdbcTemplate.queryForList(
            "SELECT section_id FROM class_sections WHERE course_id = ? LIMIT 1", Integer.class, courseId);
        
        if (sectionIds.isEmpty()) return;
        Integer sectionId = sectionIds.get(0);

        // 2. Check if space is now available
        if (!isSectionFull(sectionId)) {
            // 3. Find the first student in the queue
            List<Map<String, Object>> waitlist = jdbcTemplate.queryForList(
                "SELECT waitlist_id, student_id FROM student_waitlist " +
                "WHERE course_id = ? AND status = 'WAITING' " +
                "ORDER BY priority_date ASC LIMIT 1", courseId);

            if (!waitlist.isEmpty()) {
                // Number cast to handle different DB return types for IDs
                Long nextStudentId = ((Number) waitlist.get(0).get("student_id")).longValue();
                Integer waitlistId = (Integer) waitlist.get(0).get("waitlist_id");

                // 4. Enroll the student
                jdbcTemplate.update("INSERT INTO student_enlistments (student_id, course_id) VALUES (?, ?)", 
                    nextStudentId, courseId);
                
                // 5. Update waitlist status
                jdbcTemplate.update("UPDATE student_waitlist SET status = 'PROMOTED' WHERE waitlist_id = ?", waitlistId);
            }
        }
    }
}