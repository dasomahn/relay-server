package com.example.relayRun.club.service;

import com.example.relayRun.club.dto.*;
import com.example.relayRun.club.entity.ClubEntity;
import com.example.relayRun.club.entity.MemberStatusEntity;
import com.example.relayRun.club.entity.TimeTableEntity;
import com.example.relayRun.club.repository.ClubRepository;
import com.example.relayRun.club.repository.MemberStatusRepository;
import com.example.relayRun.club.repository.TimeTableRepository;
import com.example.relayRun.schedule.ScheduleService;
import com.example.relayRun.user.entity.UserEntity;
import com.example.relayRun.user.entity.UserProfileEntity;
import com.example.relayRun.user.repository.UserProfileRepository;
import com.example.relayRun.user.repository.UserRepository;
import com.example.relayRun.util.BaseException;
import com.example.relayRun.util.BaseResponseStatus;
import com.example.relayRun.util.RecordDataHandler;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class MemberStatusService {

    private final MemberStatusRepository memberStatusRepository;
    private final TimeTableRepository timeTableRepository;
    private final UserProfileRepository userProfileRepository;
    private final ClubRepository clubRepository;
    private final UserRepository userRepository;

    private final ScheduleService scheduleService;

    public MemberStatusService(MemberStatusRepository memberStatusRepository,
                               TimeTableRepository timeTableRepository,
                               UserProfileRepository userProfileRepository,
                               ClubRepository clubRepository,
                               UserRepository userRepository,
                               ScheduleService scheduleService) {
        this.memberStatusRepository = memberStatusRepository;
        this.timeTableRepository = timeTableRepository;
        this.userProfileRepository = userProfileRepository;
        this.clubRepository = clubRepository;
        this.userRepository = userRepository;
        this.scheduleService = scheduleService;
    }

    @Transactional(rollbackFor = BaseException.class)
    public void createMemberStatus(Long clubIdx, PostMemberStatusReq memberStatus) throws BaseException {
        try {
            Long userProfileIdx = memberStatus.getUserProfileIdx();
            Optional<UserProfileEntity> userProfile = userProfileRepository.findByUserProfileIdxAndStatus(userProfileIdx, "active");
            if (userProfile.isEmpty()) {
                throw new BaseException(BaseResponseStatus.USER_PROFILE_EMPTY);
            }

            Optional<MemberStatusEntity> optionalMemberStatus = memberStatusRepository.
                    findByUserProfileIdx_UserProfileIdxAndApplyStatusAndStatus(userProfileIdx, "ACCEPTED", "active");
            if (!optionalMemberStatus.isEmpty()) {
                // 이미 그룹 하나에 들어가있는 경우
                throw new BaseException(BaseResponseStatus.DUPLICATE_MEMBER_STATUS);
            }

            //신청 대상 그룹 정보
            Optional<ClubEntity> club = clubRepository.findByClubIdxAndStatus(clubIdx, "active");
            if (club.isEmpty()) {
                throw new BaseException(BaseResponseStatus.CLUB_UNAVAILABLE);
            }
            if (!club.get().getRecruitStatus().equals("recruiting")) {
                throw new BaseException(BaseResponseStatus.CLUB_CLOSED);
            }

            //member_status 등록
            MemberStatusEntity memberStatusEntity = MemberStatusEntity.builder()
                    .clubIdx(club.get())
                    .userProfileIdx(userProfile.get())
                    .build();

            memberStatusRepository.save(memberStatusEntity);

            // clubIdx로 누적 memberstatus 조회
            Long num = memberStatusRepository.findByClubIdx(club.get().getClubIdx(), "ACCEPTED");
            System.out.println("신청 인원 수 : "+ num);
            if(num >= club.get().getMaxNum()) { // maxNum보다 신청인원 많으면
                club.get().changeRecruitStatus("finished");
                clubRepository.save(club.get());
            }

            //Long memberStatusIdx = memberStatusEntity.getMemberStatusIdx();
            List<TimeTableDTO> timeTables = memberStatus.getTimeTables();

            // 이 함수가 실패(시간표 생성 못함)일 경우 위 memberStatus에 대한 rollback 적용
            this.createTimeTable(memberStatusEntity, timeTables);

        } catch (BaseException e) { // profile 존재 x, 그룹 존재 x, 시간표 등록 실패일 경우
            throw new BaseException(e.getStatus());
        } catch (IncorrectResultSizeDataAccessException e) { // 두개 이상의 그룹에 들어가있는 비정상 상황
            throw new BaseException(BaseResponseStatus.ERROR_DUPLICATE_CLUB);
        } catch (Exception e) { // 이외의 경우 에러처리
            throw new BaseException(BaseResponseStatus.POST_MEMBER_STATUS_FAIL);
        }
    }

    @Transactional
    public void createTimeTable(MemberStatusEntity memberStatus, List<TimeTableDTO> timeTables) throws BaseException {
        try {
//            Optional<MemberStatusEntity> memberStatusEntity = memberStatusRepository.findById(memberStatusIdx);
//            if(memberStatusEntity.isEmpty()) {
//                throw new BaseException(BaseResponseStatus.INVALID_MEMBER_STATUS);
//            }
            //하루에 두 번 뛰는 경우
            List<Integer> dayList = new ArrayList<>();
            for (TimeTableDTO timeTable : timeTables) {
                dayList.add(timeTable.getDay());
            }
            Set<Integer> daySet = new HashSet<>(dayList);
            if (daySet.size() != dayList.size()){
                throw new Exception("REPEATED");
            }

            Long clubIdx = memberStatus.getClubIdx().getClubIdx();
            for (TimeTableDTO timeTable : timeTables) {
                //중복 시간표 비교
                List<Long> duplicateTimeTableList = timeTableRepository.selectDuplicateTimeTable(clubIdx,
                        timeTable.getDay(), timeTable.getStart(), timeTable.getEnd());
                if(duplicateTimeTableList.size() > 0) {
                    throw new Exception("DUPLICATED");
                }

                TimeTableEntity timeTableEntity = TimeTableEntity.builder()
                        .memberStatusIdx(memberStatus)
                        .day(timeTable.getDay())
                        .start(timeTable.getStart())
                        .end(timeTable.getEnd())
                        .goal(timeTable.getGoal())
                        .goalType(timeTable.getGoalType())
                        .build();

                timeTableRepository.save(timeTableEntity);
                scheduleService.scheduleTimeTable(timeTableEntity);
            }
        } catch (Exception e) {
            if(e.getMessage().equals("REPEATED")) throw new BaseException(BaseResponseStatus.REPEATED_TIMETABLE);
            else if(e.getMessage().equals("DUPLICATED")) throw new BaseException(BaseResponseStatus.DUPLICATE_TIMETABLE);
            else throw new BaseException(BaseResponseStatus.POST_TIME_TABLE_FAIL);
        }
    }

    @Transactional(readOnly = true)
    public List<GetTimeTableAndUserProfileRes> getTimeTablesByClubIdx(Long clubIdx) throws BaseException {
        try {
            //1. clubIdx로 memberStatus 조회
            List<MemberStatusEntity> memberStatusEntityList = memberStatusRepository.findAllByClubIdx_ClubIdxAndApplyStatusAndStatus(clubIdx, "ACCEPTED", "active");
            if(memberStatusEntityList.isEmpty()) {
                throw new BaseException(BaseResponseStatus.CLUB_EMPTY);
            }

            List<GetTimeTableAndUserProfileRes> allTimeTableList = new ArrayList<>();

            //2. 해당 memberStatusIdx로 TimeTable 조회
            for(MemberStatusEntity memberStatusEntity : memberStatusEntityList) {
                List<GetTimeTableRes> timeTableList = new ArrayList<>(getTimeTablesByMemberStatusIdx(memberStatusEntity.getMemberStatusIdx()));

                //유저 정보
                Long userProfileIdx = memberStatusEntity.getUserProfileIdx().getUserProfileIdx();
                UserProfileEntity userProfileEntity = userProfileRepository.findOneByUserProfileIdx(userProfileIdx);

                GetTimeTableAndUserProfileRes allTimeTable = GetTimeTableAndUserProfileRes.builder()
                        .userProfileIdx(userProfileIdx)
                        .nickName(userProfileEntity.getNickName())
                        .timeTables(timeTableList)
                        .build();

                allTimeTableList.add(allTimeTable);
            }
            return allTimeTableList;
        } catch (Exception e) {
            throw new BaseException(BaseResponseStatus.DATABASE_ERROR);
        }
    }

    @Transactional(readOnly = true)
    public List<GetTimeTableRes> getUserTimeTable(Long userProfileIdx) throws BaseException {
        try {
            //memberStatusIdx 찾기
            Optional<MemberStatusEntity> memberStatusEntity = memberStatusRepository.findByUserProfileIdx_UserProfileIdxAndApplyStatusAndStatus(userProfileIdx, "ACCEPTED", "active");
            if(memberStatusEntity.isEmpty()) {
                throw new BaseException(BaseResponseStatus.USER_PROFILE_EMPTY);
            }

            //memberStatusIdx로 시간표 조회
            Long memberStatusIdx = memberStatusEntity.get().getMemberStatusIdx();

            return getTimeTablesByMemberStatusIdx(memberStatusIdx);
        } catch (BaseException e) {
            throw new BaseException(e.getStatus());
        } catch (Exception e) {
            throw new BaseException(BaseResponseStatus.DATABASE_ERROR);
        }
    }

    @Transactional
    public List<GetTimeTableRes> getTimeTablesByMemberStatusIdx(Long memberStatusIdx) throws BaseException {
        try {
            List<TimeTableEntity> timeTableEntityList = timeTableRepository.findByMemberStatusIdx_MemberStatusIdx(memberStatusIdx);
            List<GetTimeTableRes> timeTableList = new ArrayList<>();

            for(TimeTableEntity timeTableEntity : timeTableEntityList) {
                GetTimeTableRes timeTable = GetTimeTableRes.builder()
                        .timeTableIdx(timeTableEntity.getTimeTableIdx())
                        .day(timeTableEntity.getDay())
                        .start(timeTableEntity.getStart())
                        .end(timeTableEntity.getEnd())
                        .goal(timeTableEntity.getGoal())
                        .goalType(timeTableEntity.getGoalType())
                        .build();

                timeTableList.add(timeTable);
            }
            return timeTableList;
        } catch (Exception e) {
            throw new BaseException(BaseResponseStatus.DATABASE_ERROR);
        }
    }


    @Transactional
    public GetTimeTableRes getTimeTablesByMemberStatusIdxAndDate(Long memberStatusIdx, String date) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        int day = RecordDataHandler.toIntDay(LocalDateTime.parse(date+ " 00:00:00" , formatter).getDayOfWeek());
        Optional<TimeTableEntity> optionalTimeTableEntity = timeTableRepository.findByMemberStatusIdx_MemberStatusIdxAndDay(memberStatusIdx, day);
        if(optionalTimeTableEntity.isEmpty()) {
            return null;
        }
        TimeTableEntity timeTableEntity = optionalTimeTableEntity.get();
        return GetTimeTableRes.builder()
                .timeTableIdx(timeTableEntity.getTimeTableIdx())
                .day(timeTableEntity.getDay())
                .start(timeTableEntity.getStart())
                .end(timeTableEntity.getEnd())
                .goalType(timeTableEntity.getGoalType())
                .goal(timeTableEntity.getGoal())
                .build();
    }

    @Transactional(rollbackFor = BaseException.class)
    public void updateTimeTable(Long userProfileIdx, PostTimeTableReq postTimeTableReq) throws BaseException {
        try {
            //memberStatusIdx 찾기
            Optional<MemberStatusEntity> memberStatusEntity = memberStatusRepository.findByUserProfileIdx_UserProfileIdxAndApplyStatusAndStatus(userProfileIdx, "ACCEPTED", "active");
            if(memberStatusEntity.isEmpty()) {
                throw new BaseException(BaseResponseStatus.USER_PROFILE_EMPTY);
            }

            //memberStatusIdx로 시간표 조회
            Long memberStatusIdx = memberStatusEntity.get().getMemberStatusIdx();
            List<TimeTableEntity> timeTableEntityList = timeTableRepository.findByMemberStatusIdx_MemberStatusIdx(memberStatusIdx);

            // 기존 시간표 삭제
            timeTableRepository.deleteAll(timeTableEntityList);

            // 새로운 시간표 등록
            createTimeTable(memberStatusEntity.get(), postTimeTableReq.getTimeTables());

        } catch (BaseException e) {
            throw new BaseException(e.getStatus());
        } catch (Exception e) {
            throw new BaseException(BaseResponseStatus.POST_TIME_TABLE_FAIL);
        }
    }

    public void updateMemberStatus(Principal principal, Long clubIdx, Long userProfileIdx) throws BaseException {
        Optional<UserEntity> optionalUserEntity = userRepository.findByEmail(principal.getName());
        if (optionalUserEntity.isEmpty()) {
            throw new BaseException(BaseResponseStatus.FAILED_TO_LOGIN);
        }
        UserEntity userEntity = optionalUserEntity.get();

        Optional<UserProfileEntity> optionalUserProfileEntity = userProfileRepository.findByUserProfileIdxAndStatus(userProfileIdx, "active");
        if (optionalUserProfileEntity.isEmpty()) {
            throw new BaseException(BaseResponseStatus.USER_PROFILE_EMPTY);
        }
        UserProfileEntity userProfileEntity = optionalUserProfileEntity.get();

        if (!userProfileEntity.getUserIdx().equals(userEntity)) {
            throw new BaseException(BaseResponseStatus.FAILED_TO_FIND_USER);
        }

        Optional<ClubEntity> optionalClubEntity = clubRepository.findByClubIdxAndStatus(clubIdx, "active");
        if (optionalClubEntity.isEmpty()) {
            throw new BaseException(BaseResponseStatus.CLUB_UNAVAILABLE);
        }

        Optional<MemberStatusEntity> optionalMemberStatusEntity =
                memberStatusRepository.findByUserProfileIdx_UserProfileIdxAndApplyStatusAndStatus(
                        userProfileIdx, "ACCEPTED", "active");
        // 그룹에 들어가있지 않을 경우
        if (optionalMemberStatusEntity.isEmpty()) {
            throw new BaseException(BaseResponseStatus.INVALID_MEMBER_STATUS);
        }
        MemberStatusEntity memberStatusEntity = optionalMemberStatusEntity.get();

        // 요청한 club이 속한 그룹의 idx가 아닐 경우
        if (!memberStatusEntity.getClubIdx().equals(optionalClubEntity.get())) {
            throw new BaseException(BaseResponseStatus.POST_RECORD_INVALID_CLUB_ACCESS);
        }

        // 방장일 경우
        if (userProfileEntity.equals(optionalClubEntity.get().getHostIdx())) {
            throw new BaseException(BaseResponseStatus.PATCH_HOST_DROPPED_INVALID);
        }

        memberStatusEntity.setApplyStatus("LEFT");
        memberStatusRepository.save(memberStatusEntity);
    }

    public String deleteClubMember(Principal principal, Long clubIdx, PatchDeleteMemberReq request) throws BaseException {
        Optional<UserEntity> optionalUserEntity = userRepository.findByEmail(principal.getName());
        if (optionalUserEntity.isEmpty()) {
            throw new BaseException(BaseResponseStatus.FAILED_TO_LOGIN);
        }
        UserEntity userEntity = optionalUserEntity.get();

        Optional<ClubEntity> optionalClubEntity = clubRepository.findByClubIdxAndStatus(clubIdx, "active");
        if (optionalClubEntity.isEmpty()) {
            throw new BaseException(BaseResponseStatus.CLUB_UNAVAILABLE);
        }
        ClubEntity clubEntity = optionalClubEntity.get();

        if(clubEntity.getHostIdx().getUserIdx().equals(userEntity)) {
            Optional<MemberStatusEntity> optionalMemberStatusEntity =
                    memberStatusRepository.findByUserProfileIdx_UserProfileIdxAndApplyStatusAndStatus(
                            request.getUserProfileIdx(), "ACCEPTED", "active");
            if (optionalMemberStatusEntity.isEmpty()) {
                throw new BaseException(BaseResponseStatus.INVALID_MEMBER_STATUS);
            }
            MemberStatusEntity memberStatusEntity = optionalMemberStatusEntity.get();

            Optional<UserProfileEntity> optionalUserProfileEntity = userProfileRepository.findByUserProfileIdxAndStatus(request.getUserProfileIdx(), "active");
            if (optionalUserProfileEntity.isEmpty()) {
                throw new BaseException(BaseResponseStatus.USER_PROFILE_EMPTY);
            }
            UserProfileEntity userProfileEntity = optionalUserProfileEntity.get();

            if (userProfileEntity.getUserIdx().equals(userEntity)) {
                throw new BaseException(BaseResponseStatus.PATCH_HOST_DROPPED_INVALID);
            }

            memberStatusEntity.setApplyStatus("DROPPED");
            memberStatusRepository.save(memberStatusEntity);
            return userProfileEntity.getNickName();
        } else {
            throw new BaseException(BaseResponseStatus.PATCH_NOT_HOST);
        }
    }

}
