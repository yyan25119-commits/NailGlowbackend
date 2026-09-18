package com.nailglow.backend.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

@Component
public class DatabaseInitializer implements CommandLineRunner {
    private static final long USER_ID_FLOOR = 1_000_000L;
    private final JdbcTemplate jdbc;

    public DatabaseInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        createSchema();
        migrateSchema();
        seedStyles();
        ensureAdminUser();
        ensureUserIdFloor();
        ensureScoreModelVersion();
        seedSettings();
    }

    private void createSchema() {
        jdbc.execute("""
                create table if not exists nail_styles (
                  id bigint primary key auto_increment,
                  style_code varchar(40) not null default 'nail_01',
                  name varchar(120) not null,
                  tag varchar(120) not null,
                  tags varchar(300) not null,
                  description varchar(800) not null,
                  image_url varchar(800),
                  colors varchar(120) not null,
                  status varchar(20) not null,
                  try_count int not null,
                  avg_score decimal(5,2) not null,
                  created_at timestamp not null default current_timestamp,
                  updated_at timestamp not null default current_timestamp on update current_timestamp
                ) character set utf8mb4 collate utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                create table if not exists customer_photos (
                  id bigint primary key auto_increment,
                  user_id bigint,
                  style_id bigint,
                  try_on_task_id varchar(80),
                  style_name varchar(120) not null,
                  note varchar(160) not null,
                  score int not null,
                  image_url varchar(800) not null,
                  original_file_name varchar(200),
                  image_size bigint,
                  mime_type varchar(80),
                  status varchar(24) not null default 'pending',
                  reject_reason varchar(300),
                  submitted_at timestamp not null default current_timestamp,
                  reviewed_at timestamp null,
                  reviewed_by bigint,
                  created_at timestamp not null default current_timestamp
                ) character set utf8mb4 collate utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                create table if not exists customer_photo_ratings (
                  id bigint primary key auto_increment,
                  photo_id bigint not null,
                  user_id bigint not null,
                  rating int not null,
                  fit_score int,
                  color_score int,
                  style_score int,
                  scene_score int,
                  aesthetic_score int,
                  perceived_length_score int,
                  perceived_slenderness_score int,
                  perceived_palm_width_score int,
                  perceived_softness_score int,
                  comment varchar(200),
                  created_at timestamp not null default current_timestamp,
                  updated_at timestamp not null default current_timestamp on update current_timestamp,
                  unique key ux_customer_photo_rating_user(photo_id, user_id),
                  index idx_customer_photo_ratings_photo(photo_id),
                  index idx_customer_photo_ratings_user(user_id)
                ) character set utf8mb4 collate utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                create table if not exists score_model_versions (
                  id bigint primary key auto_increment,
                  version_name varchar(120) not null,
                  file_path varchar(800) not null,
                  status varchar(24) not null default 'candidate',
                  sample_count int not null default 0,
                  group_count int not null default 0,
                  questionnaire_response_count int not null default 0,
                  validation_score decimal(8,4) not null default 0,
                  schema_version varchar(64) not null default 'legacy-v1',
                  dataset_source varchar(40) not null default 'unknown',
                  metrics_json longtext,
                  file_size bigint not null default 0,
                  created_at timestamp not null default current_timestamp,
                  activated_at timestamp null,
                  index idx_score_model_status(status),
                  index idx_score_model_created(created_at)
                ) character set utf8mb4 collate utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                create table if not exists users (
                  id bigint primary key auto_increment,
                  nickname varchar(80) not null,
                  account varchar(120) not null,
                  password_hash varchar(80),
                  role varchar(20) not null,
                  status varchar(20) not null,
                  joined_at timestamp not null,
                  last_login_at timestamp not null,
                  try_count int not null,
                  favorite_style varchar(120)
                ) character set utf8mb4 collate utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                create table if not exists auth_sessions (
                  token varchar(120) primary key,
                  user_id bigint not null,
                  role varchar(20) not null,
                  expires_at timestamp not null,
                  created_at timestamp not null default current_timestamp,
                  index idx_auth_sessions_user(user_id),
                  index idx_auth_sessions_expires(expires_at)
                ) character set utf8mb4 collate utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                create table if not exists try_on_tasks (
                  id varchar(80) primary key,
                  user_id bigint not null,
                  style_ids varchar(200) not null,
                  style_names varchar(500) not null,
                  hand_photo_name varchar(200),
                  hand_photo_size bigint,
                  status varchar(24) not null,
                  score int not null,
                  result_image_url longtext,
                  advice varchar(1200),
                  metrics_json varchar(800),
                  results_json longtext,
                  provider varchar(40),
                  created_at timestamp not null default current_timestamp,
                  completed_at timestamp null
                ) character set utf8mb4 collate utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                create table if not exists appointments (
                  id bigint primary key auto_increment,
                  user_id bigint not null,
                  style_id bigint,
                  service_name varchar(120) not null,
                  slot_time varchar(80) not null,
                  scheduled_at timestamp null,
                  store_name varchar(120) not null,
                  status varchar(24) not null,
                  amount decimal(10,2) not null default 0,
                  paid_status varchar(24) not null default '未支付',
                  duration_minutes int not null default 90,
                  queue_no int not null default 0,
                  created_at timestamp not null default current_timestamp
                ) character set utf8mb4 collate utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                create table if not exists system_settings (
                  key_name varchar(80) primary key,
                  title varchar(120) not null,
                  description varchar(600) not null,
                  value_text varchar(600) not null,
                  updated_at timestamp not null default current_timestamp on update current_timestamp
                ) character set utf8mb4 collate utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                create table if not exists support_conversations (
                  id bigint primary key auto_increment,
                  user_id bigint not null,
                  status varchar(24) not null default '未处理',
                  category varchar(40) not null default '其他',
                  severity varchar(20) not null default 'low',
                  notify_merchant boolean not null default false,
                  important boolean not null default false,
                  title varchar(160),
                  summary varchar(1200),
                  important_items_json longtext,
                  latest_message varchar(1200),
                  merchant_note varchar(800),
                  handoff_requested boolean not null default false,
                  handoff_status varchar(24) not null default 'ai',
                  handoff_reason varchar(800),
                  last_message_at timestamp not null default current_timestamp,
                  created_at timestamp not null default current_timestamp,
                  updated_at timestamp not null default current_timestamp on update current_timestamp,
                  index idx_support_notify_status(notify_merchant, status),
                  index idx_support_user(user_id),
                  index idx_support_last_message(last_message_at)
                ) character set utf8mb4 collate utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                create table if not exists support_messages (
                  id bigint primary key auto_increment,
                  conversation_id bigint not null,
                  user_id bigint not null,
                  role varchar(20) not null,
                  content text not null,
                  mode varchar(40),
                  agent_source varchar(80),
                  metadata_json longtext,
                  created_at timestamp not null default current_timestamp,
                  index idx_support_messages_conversation(conversation_id)
                ) character set utf8mb4 collate utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                create table if not exists external_style_trend_batches (
                  batch_id varchar(48) primary key,
                  platform_summary_json longtext,
                  insight_summary text,
                  insight_signals_json longtext,
                  insight_actions_json longtext,
                  insight_script_text text,
                  captured_at timestamp not null default current_timestamp,
                  index idx_external_style_trend_batches_captured(captured_at)
                ) character set utf8mb4 collate utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                create table if not exists external_style_trends (
                  id bigint primary key auto_increment,
                  batch_id varchar(48) not null,
                  platform varchar(24) not null,
                  source_title varchar(240) not null,
                  style_name varchar(160) not null,
                  author_name varchar(120),
                  source_url text,
                  image_url text,
                  source_snippet text,
                  keywords varchar(300),
                  note varchar(500),
                  like_count bigint not null default 0,
                  like_text varchar(40),
                  heat_score decimal(8,2) not null default 0,
                  rank_no int not null default 0,
                  capture_method varchar(24) not null default 'agent',
                  publish_status varchar(24) not null default 'pending',
                  published_style_id bigint,
                  published_at timestamp null,
                  created_at timestamp not null default current_timestamp,
                  captured_at timestamp not null default current_timestamp,
                  index idx_external_style_trends_batch(batch_id),
                  index idx_external_style_trends_platform_rank(platform, rank_no),
                  index idx_external_style_trends_captured(captured_at)
                ) character set utf8mb4 collate utf8mb4_unicode_ci
                """);
    }

    private void migrateSchema() {
        addColumnIfMissing("alter table nail_styles add column style_code varchar(40) not null default 'nail_01' after id");
        addColumnIfMissing("alter table users add column password_hash varchar(80) after account");
        addColumnIfMissing("alter table users add unique index ux_users_account(account)");
        addColumnIfMissing("alter table customer_photos add column user_id bigint after id");
        addColumnIfMissing("alter table customer_photos add column style_id bigint after user_id");
        addColumnIfMissing("alter table customer_photos add column try_on_task_id varchar(80) after style_id");
        addColumnIfMissing("alter table customer_photos add column original_file_name varchar(200) after image_url");
        addColumnIfMissing("alter table customer_photos add column image_size bigint after original_file_name");
        addColumnIfMissing("alter table customer_photos add column mime_type varchar(80) after image_size");
        addColumnIfMissing("alter table customer_photos add column status varchar(24) not null default 'approved' after mime_type");
        addColumnIfMissing("alter table customer_photos add column reject_reason varchar(300) after status");
        addColumnIfMissing("alter table customer_photos add column submitted_at timestamp not null default current_timestamp after reject_reason");
        addColumnIfMissing("alter table customer_photos add column reviewed_at timestamp null after submitted_at");
        addColumnIfMissing("alter table customer_photos add column reviewed_by bigint after reviewed_at");
        addColumnIfMissing("alter table customer_photos add index idx_customer_photos_status_created(status, created_at)");
        addColumnIfMissing("alter table customer_photos add index idx_customer_photos_user(user_id)");
        addColumnIfMissing("alter table customer_photo_ratings add column fit_score int after rating");
        addColumnIfMissing("alter table customer_photo_ratings add column color_score int after fit_score");
        addColumnIfMissing("alter table customer_photo_ratings add column style_score int after color_score");
        addColumnIfMissing("alter table customer_photo_ratings add column scene_score int after style_score");
        addColumnIfMissing("alter table customer_photo_ratings add column aesthetic_score int after scene_score");
        addColumnIfMissing("alter table customer_photo_ratings add column perceived_length_score int after aesthetic_score");
        addColumnIfMissing("alter table customer_photo_ratings add column perceived_slenderness_score int after perceived_length_score");
        addColumnIfMissing("alter table customer_photo_ratings add column perceived_palm_width_score int after perceived_slenderness_score");
        addColumnIfMissing("alter table customer_photo_ratings add column perceived_softness_score int after perceived_palm_width_score");
        addColumnIfMissing("alter table customer_photo_ratings add column comment varchar(200) after style_score");
        addColumnIfMissing("alter table score_model_versions add column sample_count int not null default 0 after status");
        addColumnIfMissing("alter table score_model_versions add column group_count int not null default 0 after sample_count");
        addColumnIfMissing("alter table score_model_versions add column questionnaire_response_count int not null default 0 after group_count");
        addColumnIfMissing("alter table score_model_versions add column validation_score decimal(8,4) not null default 0 after questionnaire_response_count");
        addColumnIfMissing("alter table score_model_versions add column schema_version varchar(64) not null default 'legacy-v1' after validation_score");
        addColumnIfMissing("alter table score_model_versions add column dataset_source varchar(40) not null default 'unknown' after schema_version");
        addColumnIfMissing("alter table score_model_versions add column metrics_json longtext after dataset_source");
        addColumnIfMissing("alter table score_model_versions add column file_size bigint not null default 0 after validation_score");
        addColumnIfMissing("alter table try_on_tasks add column results_json longtext after metrics_json");
        addColumnIfMissing("alter table appointments add column scheduled_at timestamp null after slot_time");
        addColumnIfMissing("alter table appointments add column amount decimal(10,2) not null default 0 after status");
        addColumnIfMissing("alter table appointments add column paid_status varchar(24) not null default '未支付' after amount");
        addColumnIfMissing("alter table appointments add column duration_minutes int not null default 90 after paid_status");
        addColumnIfMissing("alter table appointments add column queue_no int not null default 0 after duration_minutes");
        addColumnIfMissing("alter table support_conversations add column handoff_requested boolean not null default false after merchant_note");
        addColumnIfMissing("alter table support_conversations add column handoff_status varchar(24) not null default 'ai' after handoff_requested");
        addColumnIfMissing("alter table support_conversations add column handoff_reason varchar(800) after handoff_status");
        addColumnIfMissing("alter table external_style_trends add column author_name varchar(120) after style_name");
        addColumnIfMissing("alter table external_style_trends modify column source_url text");
        addColumnIfMissing("alter table external_style_trends modify column image_url text");
        addColumnIfMissing("alter table external_style_trends modify column source_snippet text");
        addColumnIfMissing("alter table external_style_trends add column like_count bigint not null default 0 after note");
        addColumnIfMissing("alter table external_style_trends add column like_text varchar(40) after like_count");
        addColumnIfMissing("alter table external_style_trends modify column capture_method varchar(24) not null default 'agent'");
        addColumnIfMissing("alter table external_style_trends add column publish_status varchar(24) not null default 'pending' after capture_method");
        addColumnIfMissing("alter table external_style_trends add column published_style_id bigint after publish_status");
        addColumnIfMissing("alter table external_style_trends add column published_at timestamp null after published_style_id");
        addColumnIfMissing("alter table external_style_trend_batches add column insight_script_text text after insight_actions_json");
        jdbc.update("""
                update appointments
                set amount = case service_name
                    when 'AI 试穿复刻' then 268
                    when '款式微调' then 198
                    when '卸甲护理' then 88
                    when '作品确认' then 128
                    else amount
                end
                where amount = 0
                """);
        jdbc.update("""
                update appointments
                set duration_minutes = case service_name
                    when 'AI 试穿复刻' then 110
                    when '款式微调' then 80
                    when '卸甲护理' then 45
                    when '作品确认' then 60
                    else duration_minutes
                end
                where duration_minutes = 90
                """);
        jdbc.update("""
                update appointments a
                join (
                    select user_id, max(id) as keep_id
                    from appointments
                    where status not in ('已取消', '已完成')
                    group by user_id
                    having count(*) > 1
                ) latest on latest.user_id = a.user_id
                set a.status = '已取消'
                where a.status not in ('已取消', '已完成')
                  and a.id <> latest.keep_id
                """);
    }

    private void addColumnIfMissing(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception ignored) {
            // Existing local databases already have this column.
        }
    }

    private void seedStyles() {
        Integer count = jdbc.queryForObject("select count(*) from nail_styles", Integer.class);
        if (count != null && count >= 25 && !hasCorruptedText("nail_styles", "name")) {
            jdbc.update("update nail_styles set style_code = 'nail_01' where style_code is null or style_code = ''");
            return;
        }
        jdbc.update("delete from nail_styles");
        jdbc.execute("alter table nail_styles auto_increment = 1");

        List<Object[]> rows = List.of(
                row("nail_02", "奶油法式通勤款", "通勤 / 显白", "通勤,显白,法式", "奶油裸粉底色搭配细白边，温柔干净，适合日常办公和约会。", "http://p0.meituan.net/pilotimages/87797733466cfd525625a5947767e2ff1794125.png", "#f5e0d0,#fff8f0,#c9a088", "上架", 1286, 92.4),
                row("nail_01", "冰透裸粉渐变", "清透 / 显白", "清透,显白,高级感", "透粉渐变叠加水润光泽，视觉上拉长甲床，适合多数手型。", "http://p0.meituan.net/pilotimages/162afb52255bd908ba3ec418fd61824a2254875.png", "#ffcbd4,#f4f8ff,#e9829c", "上架", 1568, 91.6),
                row("nail_05", "樱花粉渐变美甲", "温柔 / 春日", "温柔,渐变,春日", "樱花粉过渡柔和，保留自然甲面通透感，拍照很出片。", "http://p1.meituan.net/pilotimages/7bb5bc0c2c741f9f0aa63787a601d7ad2604877.png", "#ffb7c5,#ffe4ec,#c98f83", "上架", 1438, 92.0),
                row("nail_04", "香芋紫蝴蝶美甲", "梦幻 / 亮片", "香芋紫,蝴蝶,亮片", "香芋紫底色搭配蝴蝶与细闪，适合甜美和约会场景。", "http://p0.meituan.net/pilotimages/fc8fe60e78341d77a5070fc2f8e520072098070.png", "#cdb4db,#f4e7ff,#9b7bb7", "上架", 1189, 90.7),
                row("nail_02", "奶油白法式美甲", "极简 / 法式", "极简,法式,显白", "奶油白法式边清爽耐看，适合短甲、方圆甲和通勤造型。", "http://p1.meituan.net/pilotimages/3c0d090e20f0cb56f70fcb56c54dd6582416974.png", "#fff7ee,#f6dcc9,#d6a98f", "上架", 1324, 91.8),
                row("nail_03", "落日橘晕染美甲", "暖调 / 气色", "暖调,晕染,元气", "落日橘晕染提升气色，适合暖皮和节日穿搭。", "http://p0.meituan.net/pilotimages/6c857edd85a5fa4bcec59698fe9416cb1913981.png", "#f7a072,#ffd6ba,#c8553d", "上架", 987, 89.9),
                row("nail_04", "白月光珍珠美甲", "优雅 / 婚礼", "优雅,珍珠,新娘", "乳白底色搭配珍珠和细闪，干净高级，适合婚礼和重要场合。", "http://p0.meituan.net/pilotimages/2ac2d01a9bc78320edbe2b545b485b4a2132292.png", "#fefefe,#f5f0eb,#e8ddd3", "上架", 1342, 93.1),
                row("nail_02", "小香风格纹美甲", "时尚 / 高级", "时尚,格纹,撞色", "黑白格纹加入少量彩色线条，经典但更有活力。", "http://p1.meituan.net/pilotimages/d15c06e8c2137d4f39f3b60476a90cf92026957.png", "#1a1a1a,#f5f5f5,#e07b5a", "上架", 743, 89.8),
                row("nail_03", "车厘子红美甲", "复古 / 气场", "复古,显白,酒红", "车厘子红显白有气场，适合短甲和方圆甲。", "http://p1.meituan.net/pilotimages/69614397f0ecb559b98cb46a5a46f3b32642714.png", "#8b1a2a,#c93048,#5c1020", "上架", 1678, 93.5),
                row("nail_04", "樱花猫眼美甲", "温柔 / 浪漫", "温柔,猫眼,春日", "樱花粉底色配合细腻猫眼光带，柔和不夸张。", "http://p1.meituan.net/pilotimages/2277d6f9d82264fa6a3c986373e5e44c2292083.png", "#ffb7c5,#ffe4ec,#c98f83", "上架", 1456, 92.0),
                row("nail_02", "高级灰银边美甲", "冷调 / 精致", "冷调,银边,高级感", "高级灰搭配细银边，线条利落，适合冷淡风穿搭。", "http://p0.meituan.net/pilotimages/bc153edf655dd6961dc9f8e95ad8cd1e2561531.png", "#9aa0a6,#f1f3f5,#c9cdd2", "上架", 1122, 90.9),
                row("nail_03", "焦糖棕晕染美甲", "秋冬 / 温暖", "秋冬,焦糖,晕染", "焦糖棕晕染温暖显气质，适合秋冬和大地色穿搭。", "http://p0.meituan.net/pilotimages/43cc4ced977a3dd271f60ee2f05607772681747.png", "#8b5e3c,#d4af37,#f5e6d3", "上架", 1065, 91.5),
                row("nail_04", "雾霾蓝磨砂美甲", "冷调 / 磨砂", "雾霾蓝,磨砂,高级感", "雾霾蓝磨砂质感克制清冷，适合长甲和极简穿搭。", "http://p0.meituan.net/pilotimages/682c173ae3a95d0b838655e8337b30d72213857.png", "#9fb7c9,#d7e5ee,#6e8797", "上架", 998, 90.6),
                row("nail_01", "冰透裸粉渐变·水光版", "水光 / 裸粉", "水光,裸粉,显白", "在裸粉渐变上增强水光感，通透显白，适合自然光返图。", "http://p1.meituan.net/pilotimages/eecfba4ab276e895b579a79491b2d0211982788.png", "#f8cbd5,#fff4f7,#d995a7", "上架", 1492, 92.8),
                row("nail_03", "车厘子红美甲·酒红版", "酒红 / 显白", "酒红,显白,复古", "更深的酒红色调，显白并强化复古氛围。", "http://p0.meituan.net/pilotimages/1248ad42d355b98257e5fbcdf90efc552138079.png", "#771322,#b62036,#4c0c18", "上架", 1217, 92.6),
                row("nail_04", "白月光珍珠美甲·满钻版", "闪钻 / 仪式感", "珍珠,满钻,婚礼", "珍珠与细钻密度更高，适合婚礼、写真和派对。", "http://p0.meituan.net/pilotimages/137aad1f6a36655ae395cf7dc57604642782680.png", "#ffffff,#f1e8df,#d8c7bc", "上架", 906, 91.9),
                row("nail_02", "小香风格纹美甲·彩色版", "格纹 / 彩色", "格纹,彩色,时尚", "小香格纹加入彩色跳色，适合活泼但保留精致感的造型。", "http://p0.meituan.net/pilotimages/ec437f6291295904c2f894edb8c01cb82131722.png", "#1d1d1f,#ffffff,#f06b8a", "上架", 873, 90.3),
                row("nail_04", "樱花猫眼美甲·极光版", "极光 / 猫眼", "极光,猫眼,浪漫", "猫眼光带更明显，叠加极光细闪，适合拍照展示。", "http://p0.meituan.net/pilotimages/5591229138c4e7e1d183b59be442d9dc2267735.png", "#f8bfd0,#f5eaff,#b98ed8", "上架", 1006, 92.1),
                row("nail_04", "高级灰银边美甲·碎钻版", "灰调 / 碎钻", "灰调,银边,碎钻", "高级灰银边加入碎钻点缀，克制但有细节。", "http://p0.meituan.net/pilotimages/5fad21e6d38656170bf726ff3973a4501918338.png", "#8b9199,#f4f4f4,#c6ccd6", "上架", 792, 90.5),
                row("nail_03", "焦糖棕晕染美甲·金箔版", "金箔 / 晕染", "焦糖,金箔,晕染", "焦糖棕晕染搭配手工金箔，温暖且有层次。", "http://p1.meituan.net/pilotimages/d5eedc75b0021f79381962fc145b0bc62301165.png", "#8b5e3c,#d4af37,#f5e6d3", "上架", 921, 91.5),
                row("nail_05", "蜜桃乌龙美甲", "蜜桃 / 日常", "蜜桃,日常,温柔", "蜜桃乌龙色调轻盈温柔，适合短甲和日常通勤。", "http://p0.meituan.net/pilotimages/f4b69d45af5d3b496adbd9d21e768a8e2195181.png", "#f4a7a1,#ffd9cf,#d77973", "上架", 1178, 91.2),
                row("nail_03", "车厘子红美甲·丝绒版", "丝绒 / 复古", "丝绒,酒红,显白", "丝绒质感压低光泽，更显复古与高级。", "http://p0.meituan.net/pilotimages/5b985a1c661ae2e964286178e6c0b0f92258113.png", "#7f1828,#b63243,#4e101b", "上架", 1081, 92.2),
                row("nail_04", "薄荷绿渐变美甲", "清新 / 夏日", "清新,渐变,短甲", "薄荷绿过渡到透明边缘，清爽轻盈，适合夏天和自然光拍摄。", "http://p1.meituan.net/pilotimages/bf8657d94693fb0fe1da3f7729d5667d2020119.png", "#7ed6c0,#c8f0e8,#3da88a", "上架", 1098, 90.8),
                row("nail_04", "香芋紫蝴蝶美甲·星空版", "星空 / 蝴蝶", "香芋紫,蝴蝶,星空", "香芋紫叠加星空碎闪，视觉更梦幻。", "http://p0.meituan.net/pilotimages/e80e1d25e48d7ef5c505b29ee8e331822641412.png", "#b89bd3,#efe3ff,#7357a6", "上架", 857, 90.1),
                row("nail_02", "奶油白法式美甲·铆钉版", "法式 / 个性", "法式,铆钉,个性", "奶油白法式边搭配少量铆钉，干净中带一点个性。", "http://p1.meituan.net/pilotimages/73ee568aa09547d8bfc0168113ac9ebc2712329.png", "#fff8f1,#e8d2c0,#a97962", "上架", 814, 90.4)
        );
        jdbc.batchUpdate("""
                insert into nail_styles(style_code, name, tag, tags, description, image_url, colors, status, try_count, avg_score)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    private void seedCustomerPhotos() {
        Integer count = jdbc.queryForObject("select count(*) from customer_photos", Integer.class);
        if (count != null && count > 0 && !hasCorruptedText("customer_photos", "style_name")) return;
        jdbc.update("delete from customer_photos");
        jdbc.execute("alter table customer_photos auto_increment = 1");
        jdbc.batchUpdate("""
                insert into customer_photos(style_name, note, score, image_url)
                values (?, ?, ?, ?)
                """, List.of(
                photo("冰透裸粉渐变", "自然光返图", 96, "http://p0.meituan.net/pilotimages/162afb52255bd908ba3ec418fd61824a2254875.png"),
                photo("奶油法式通勤款", "通勤短甲返图", 94, "http://p0.meituan.net/pilotimages/87797733466cfd525625a5947767e2ff1794125.png"),
                photo("白月光珍珠美甲", "显白细闪返图", 95, "http://p0.meituan.net/pilotimages/2ac2d01a9bc78320edbe2b545b485b4a2132292.png"),
                photo("樱花猫眼美甲", "约会柔光返图", 93, "http://p1.meituan.net/pilotimages/2277d6f9d82264fa6a3c986373e5e44c2292083.png"),
                photo("车厘子红美甲", "复古酒红返图", 97, "http://p1.meituan.net/pilotimages/69614397f0ecb559b98cb46a5a46f3b32642714.png")
        ));
    }

    private boolean hasCorruptedText(String table, String column) {
        try {
            String sample = jdbc.queryForObject("select " + column + " from " + table + " order by id limit 1", String.class);
            return sample == null
                    || sample.contains("?")
                    || sample.matches(".*[ÃÂåæçèé].*");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void ensureAdminUser() {
        String adminPassword = System.getenv().getOrDefault("NAILGLOW_ADMIN_PASSWORD", "admin");
        Integer count = jdbc.queryForObject("select count(*) from users where account = 'admin' and role = 'admin'", Integer.class);
        if (count != null && count > 0) {
            jdbc.update("""
                    update users
                    set nickname = '管理员', password_hash = ?, status = '正常', favorite_style = null
                    where account = 'admin' and role = 'admin'
                    """, AuthService.hashPassword(adminPassword));
            return;
        }
        jdbc.update("""
                insert into users(nickname, account, password_hash, role, status, joined_at, last_login_at, try_count, favorite_style)
                values ('管理员', 'admin', ?, 'admin', '正常', current_timestamp, current_timestamp, 0, null)
                """, AuthService.hashPassword(adminPassword));
    }

    private void ensureUserIdFloor() {
        try {
            Long nextValue = jdbc.queryForObject("""
                    select auto_increment
                    from information_schema.tables
                    where table_schema = database()
                      and table_name = 'users'
                    """, Long.class);
            if (nextValue == null || nextValue < USER_ID_FLOOR) {
                jdbc.execute("alter table users auto_increment = " + USER_ID_FLOOR);
            }
        } catch (Exception ignored) {
            // MySQL sets the real next value to max(id) + 1 if that is already higher.
        }
    }

    private void ensureScoreModelVersion() {
        Integer count = jdbc.queryForObject("select count(*) from score_model_versions", Integer.class);
        if (count != null && count > 0) return;
        Path managed = Path.of("models", "score_model.joblib").toAbsolutePath().normalize();
        Path root = Path.of("score_model.joblib").toAbsolutePath().normalize();
        Path path = Files.exists(managed) ? managed : root;
        long size = 0L;
        try {
            size = Files.exists(path) ? Files.size(path) : 0L;
        } catch (Exception ignored) {
        }
        Integer sampleCount = jdbc.queryForObject("select count(*) from customer_photo_ratings", Integer.class);
        jdbc.update("""
                insert into score_model_versions(
                  version_name, file_path, status, sample_count, group_count,
                  questionnaire_response_count, validation_score, schema_version,
                  dataset_source, metrics_json, file_size, created_at, activated_at
                ) values (
                  'score_model.joblib', ?, 'active', ?, 0, ?, 0, 'legacy-v1',
                  'bootstrap', '{}', ?, current_timestamp, current_timestamp
                )
                """, path.toString(), sampleCount == null ? 0 : sampleCount,
                sampleCount == null ? 0 : sampleCount, size);
    }

    private void seedSettings() {
        ensureSettingRow("shared_ai_api_key", "共享 AI API Key", "供客服 AI、路线规划 AI、流行趋势 AI 复用；未填写时自动读取环境变量。", "");
        ensureSettingRow("doubao_api_key", "试戴美甲 AI Key", "用于 AI 美甲试穿图生成；未填写时自动回退到共享 AI Key 或环境变量。", "");
        ensureSettingRow("doubao_endpoint", "试戴美甲 API URL", "用于 AI 美甲试穿图生成；可填写 Lumio 基础地址或完整 /v1/images/generations。", "https://api.lumio.games/");
        ensureSettingRow("doubao_model", "试戴美甲 AI 模型", "用于 AI 美甲试穿图生成；默认建议 gpt-image-2。", "gpt-image-2");
        ensureSettingRow("doubao_size", "试戴美甲 图片尺寸", "用于 AI 美甲试穿图生成；支持 1k / 1024x1024 等尺寸。", "1024x1024");
        ensureSettingRow("customer_agent_base_url", "客服 AI URL", "用于售前、售后、预约和排队客服 Agent，可填写完整模型网关基础地址。", "https://ark.cn-beijing.volces.com/api/v3");
        ensureSettingRow("customer_agent_api_key", "客服 AI Key", "用于售前、售后、预约和排队客服 Agent；未填写时自动回退到共享 AI Key 或环境变量。", "");
        ensureSettingRow("route_agent_base_url", "路线规划 AI URL", "用于门店推荐、路线整理和到店建议，可填写完整模型网关基础地址。", "https://ark.cn-beijing.volces.com/api/v3");
        ensureSettingRow("route_agent_api_key", "路线规划 AI Key", "用于门店推荐、路线整理和到店建议；未填写时自动回退到共享 AI Key 或环境变量。", "");
        ensureSettingRow("trend_agent_base_url", "流行趋势 AI URL", "用于热门款式检索词生成、趋势分析和运营话术生成，可填写完整模型网关基础地址。", "https://ark.cn-beijing.volces.com/api/v3");
        ensureSettingRow("trend_agent_api_key", "流行趋势 AI Key", "用于热门款式检索词生成、趋势分析和运营话术生成；未填写时自动回退到共享 AI Key 或环境变量。", "");
        ensureSettingRow("daily_report_base_url", "运营日报 AI URL", "只给运营日报总结、客诉诊断和低分款式优化建议使用，可填写基础地址或完整 /chat/completions。", "https://ark.cn-beijing.volces.com/api/v3");
        ensureSettingRow("daily_report_api_key", "运营日报专用 AI Key", "只给运营日报总结、客诉诊断和低分款式优化建议使用；未填写时自动回退到共享 AI Key 或火山引擎环境变量。", "");
        ensureSettingRow("amap_web_service_key", "高德地图 API Key", "用于路线规划 AI 调用高德 Web Service；未填写时自动读取环境变量。", "");
        ensureSettingRow("customer_agent_model", "客服 AI 模型", "用于售前、售后、预约和排队客服 Agent。", "doubao-seed-2-0-pro-260215");
        ensureSettingRow("route_agent_model", "路线规划 AI 模型", "用于门店推荐、路线整理和到店建议。", "doubao-seed-2-0-pro-260215");
        ensureSettingRow("trend_agent_model", "流行趋势 AI 模型", "用于热门款式检索词生成、趋势分析和运营话术生成。", "doubao-seed-2-0-pro-260215");
        ensureSettingRow("daily_report_model", "运营日报专用 AI 模型", "只给日报总结、客诉归因和运营建议使用，默认使用火山引擎豆包文本模型。", "doubao-seed-2-0-pro-260215");
        ensureSettingValueIfLegacy("doubao_endpoint", "https://api.lumio.games/", "https://ark.cn-beijing.volces.com/api/v3/images/generations");
        ensureSettingValueIfLegacy("doubao_model", "gpt-image-2", "doubao-seedream-4-0-250828");
        removeSettingRow("score_rule");
        removeSettingRow("upload_limit");
        removeSettingRow("store_capacity");
    }

    private void ensureSettingRow(String key, String title, String description, String defaultValue) {
        Integer exists = jdbc.queryForObject("select count(*) from system_settings where key_name = ?", Integer.class, key);
        if (exists != null && exists > 0) {
            jdbc.update("""
                    update system_settings
                    set title = ?, description = ?
                    where key_name = ?
                    """, title, description, key);
            return;
        }
        jdbc.update("""
                insert into system_settings(key_name, title, description, value_text)
                values (?, ?, ?, ?)
                """, key, title, description, defaultValue);
    }

    private void removeSettingRow(String key) {
        jdbc.update("delete from system_settings where key_name = ?", key);
    }

    private void ensureSettingValueIfLegacy(String key, String desiredValue, String... legacyValues) {
        try {
            String current = jdbc.queryForObject("select value_text from system_settings where key_name = ?", String.class, key);
            if (current == null || current.isBlank()) {
                jdbc.update("update system_settings set value_text = ?, updated_at = current_timestamp where key_name = ?",
                        desiredValue, key);
                return;
            }
            for (String legacyValue : legacyValues) {
                if (Objects.equals(current.trim(), legacyValue)) {
                    jdbc.update("update system_settings set value_text = ?, updated_at = current_timestamp where key_name = ?",
                            desiredValue, key);
                    return;
                }
            }
        } catch (Exception ignored) {
            // Best-effort migration only.
        }
    }

    private Object[] row(String styleCode, String name, String tag, String tags, String description, String imageUrl,
                         String colors, String status, int tryCount, double avgScore) {
        return new Object[]{styleCode, name, tag, tags, description, imageUrl, colors, status, 0, 0.0};
    }

    private Object[] photo(String styleName, String note, int score, String imageUrl) {
        return new Object[]{styleName, note, score, imageUrl};
    }

}
