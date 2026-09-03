package com.youkeda.exercise.claw.feature.map;

import com.youkeda.exercise.claw.ai.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地点名称翻译器
 *
 * <p>将中文地名/城市名翻译为英文标准名称，用于 Pexels 图片搜索查询词优化。
 * 翻译策略（按优先级）：
 * <ol>
 *   <li><b>热门地点字典</b> — 覆盖国内主要景点，零延迟</li>
 *   <li><b>城市字典</b> — 中文城市名→英文，纯字典无 LLM 调用</li>
 *   <li><b>LLM 翻译</b> — 地点字典未命中时通过 LLM 翻译</li>
 *   <li><b>缓存</b> — 翻译结果 ConcurrentHashMap 缓存，减少重复调用</li>
 * </ol>
 */
@Component
public class PlaceNameTranslator {

    private static final Logger log = LoggerFactory.getLogger(PlaceNameTranslator.class);

    /** 热门地点中英对照字典 */
    private static final Map<String, String> HOT_PLACES = createHotPlaces();

    /** 翻译缓存（上限 200 条） */
    private final Map<String, String> translationCache = new ConcurrentHashMap<>();

    /** LLM 翻译系统提示词 */
    private static final String TRANSLATE_PROMPT =
            "你是一个地名翻译助手。将用户输入的中文地名翻译为英文标准名称。\n"
                    + "要求：只返回英文名称本身，不要附加任何解释、引号、标点或多余字符。\n"
                    + "如果输入已经是英文或无法识别，直接返回原文。";

    private final LLMClient llmClient;

    public PlaceNameTranslator(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 将中文地点名翻译为英文
     *
     * @param chineseName 中文地点名称
     * @return 英文标准名称，翻译失败时返回 null
     */
    public String translate(String chineseName) {
        if (chineseName == null || chineseName.isBlank()) return null;

        String trimmed = chineseName.trim();

        // 已经是英文，无需翻译
        if (isAscii(trimmed)) return trimmed;

        // 1. 查缓存
        String cached = translationCache.get(trimmed);
        if (cached != null) return cached;

        // 2. 查热门地点字典（子串匹配，支持"故宫博物院"→"故宫"→"The Palace Museum"）
        for (Map.Entry<String, String> entry : HOT_PLACES.entrySet()) {
            if (trimmed.contains(entry.getKey()) || entry.getKey().contains(trimmed)) {
                translationCache.put(trimmed, entry.getValue());
                log.debug("热门地点字典命中 | chinese={} | english={}", trimmed, entry.getValue());
                return entry.getValue();
            }
        }

        // 3. LLM 翻译
        try {
            String english = llmClient.chatWithSystemPrompt(TRANSLATE_PROMPT, trimmed);
            if (english != null && !english.isBlank()) {
                String cleaned = english.trim();
                // LLM 返回的内容应是英文
                if (isAscii(cleaned.replaceAll("\\s+", ""))) {
                    translationCache.put(trimmed, cleaned);
                    log.info("LLM 翻译 | chinese={} | english={}", trimmed, cleaned);
                    return cleaned;
                }
            }
        } catch (Exception e) {
            log.warn("LLM 翻译失败 | chinese={} | error={}", trimmed, e.getMessage());
        }

        return null;
    }

    // ==================== 城市翻译 ====================

    /** 中文城市 → 英文标准名映射（纯字典，不调用 LLM） */
    private static final Map<String, String> CITY_MAP = createCityMap();

    /**
     * 将中文城市名称翻译为英文
     *
     * <p>仅使用字典映射，不调用 LLM。
     *
     * @param chineseCity 中文城市名，如"北京"、"杭州"
     * @return 英文城市名，如"Beijing"、"Hangzhou"；不在字典中时返回原值
     */
    public String translateCity(String chineseCity) {
        if (chineseCity == null || chineseCity.isBlank()) return chineseCity;
        // 已经是英文
        if (isAscii(chineseCity.trim())) return chineseCity.trim();

        String city = chineseCity.trim();
        // 移除"市"后缀
        if (city.endsWith("市")) {
            city = city.substring(0, city.length() - 1);
        }
        String english = CITY_MAP.get(city);
        return english != null ? english : chineseCity.trim();
    }

    /**
     * 清空翻译缓存
     */
    public void clearCache() {
        translationCache.clear();
    }

    // ==================== Internal ====================

    private static boolean isAscii(String text) {
        if (text == null || text.isBlank()) return false;
        return text.chars().allMatch(c -> c < 128 || Character.isWhitespace(c));
    }

    private static Map<String, String> createHotPlaces() {
        Map<String, String> map = new LinkedHashMap<>();

        // ===== 北京 =====
        map.put("天安门", "Tiananmen Square");
        map.put("天安门广场", "Tiananmen Square");
        map.put("天安门城楼", "Tiananmen Square");
        map.put("故宫", "The Palace Museum");
        map.put("故宫博物院", "The Palace Museum");
        map.put("紫禁城", "Forbidden City");
        map.put("八达岭长城", "Great Wall of China");
        map.put("长城", "Great Wall of China");
        map.put("颐和园", "Summer Palace");
        map.put("圆明园", "Old Summer Palace");
        map.put("天坛", "Temple of Heaven");
        map.put("鸟巢", "Bird's Nest");
        map.put("国家体育场", "Bird's Nest");
        map.put("水立方", "Water Cube");
        map.put("国家游泳中心", "Water Cube");
        map.put("南锣鼓巷", "Nanluoguxiang");
        map.put("王府井", "Wangfujing");
        map.put("三里屯", "Sanlitun");
        map.put("北海公园", "Beihai Park");
        map.put("景山公园", "Jingshan Park");
        map.put("香山", "Fragrant Hills");
        map.put("十三陵", "Ming Tombs");
        map.put("雍和宫", "Yonghe Temple");
        map.put("什刹海", "Shichahai");
        map.put("后海", "Houhai");
        map.put("798", "798 Art Zone");
        map.put("北京大栅栏", "Dashilan");

        // ===== 西安 =====
        map.put("兵马俑", "Terracotta Warriors");
        map.put("秦始皇兵马俑", "Terracotta Warriors");
        map.put("秦始皇陵", "Mausoleum of Qin Shihuang");
        map.put("大雁塔", "Great Wild Goose Pagoda");
        map.put("小雁塔", "Small Wild Goose Pagoda");
        map.put("华清宫", "Huaqing Palace");
        map.put("华清池", "Huaqing Pool");
        map.put("回民街", "Muslim Street");
        map.put("西安钟楼", "Bell Tower Xi'an");
        map.put("西安鼓楼", "Drum Tower Xi'an");
        map.put("西安城墙", "Xi'an City Wall");
        map.put("大唐不夜城", "Great Tang All Day Mall");

        // ===== 上海 =====
        map.put("外滩", "The Bund");
        map.put("东方明珠", "Oriental Pearl Tower");
        map.put("东方明珠塔", "Oriental Pearl Tower");
        map.put("上海中心大厦", "Shanghai Tower");
        map.put("上海中心", "Shanghai Tower");
        map.put("南京路", "Nanjing Road");
        map.put("城隍庙", "City God Temple");
        map.put("豫园", "Yu Garden");
        map.put("迪士尼", "Disneyland Shanghai");
        map.put("上海迪士尼", "Disneyland Shanghai");
        map.put("陆家嘴", "Lujiazui");
        map.put("新天地", "Xintiandi");
        map.put("田子坊", "Tianzifang");
        map.put("朱家角", "Zhujiajiao");
        map.put("上海科技馆", "Shanghai Science Museum");

        // ===== 杭州 =====
        map.put("西湖", "West Lake");
        map.put("灵隐寺", "Lingyin Temple");
        map.put("雷峰塔", "Leifeng Pagoda");
        map.put("断桥", "Broken Bridge");
        map.put("苏堤", "Su Causeway");
        map.put("宋城", "Songcheng");
        map.put("千岛湖", "Thousand Island Lake");
        map.put("西溪湿地", "Xixi Wetland");
        map.put("钱塘江", "Qiantang River");
        map.put("龙井村", "Longjing Village");
        map.put("九溪十八涧", "Nine Creeks");

        // ===== 苏州 =====
        map.put("拙政园", "Zhuozheng Garden");
        map.put("留园", "Lingering Garden");
        map.put("虎丘", "Tiger Hill");
        map.put("寒山寺", "Hanshan Temple");
        map.put("苏州园林", "Suzhou Gardens");
        map.put("周庄", "Zhouzhuang");
        map.put("同里", "Tongli");
        map.put("金鸡湖", "Jinji Lake");
        map.put("平江路", "Pingjiang Road");
        map.put("山塘街", "Shantang Street");
        map.put("苏州博物馆", "Suzhou Museum");

        // ===== 南京 =====
        map.put("夫子庙", "Confucius Temple");
        map.put("中山陵", "Sun Yat-sen Mausoleum");
        map.put("明孝陵", "Ming Xiaoling Mausoleum");
        map.put("总统府", "Presidential Palace");
        map.put("玄武湖", "Xuanwu Lake");
        map.put("秦淮河", "Qinhuai River");
        map.put("鸡鸣寺", "Jiming Temple");
        map.put("南京大报恩寺", "Porcelain Tower");
        map.put("牛首山", "Niushou Mountain");

        // ===== 无锡 =====
        map.put("鼋头渚", "Yuantouzhu");
        map.put("拈花湾", "Nianhua Bay");
        map.put("灵山大佛", "Lingshan Great Buddha");
        map.put("灵山", "Lingshan");
        map.put("三国城", "Three Kingdoms City");
        map.put("水浒城", "Water Margin City");
        map.put("蠡园", "Li Garden");
        map.put("惠山古镇", "Huishan Ancient Town");
        map.put("梅园", "Mei Garden");
        map.put("荡口古镇", "Dangkou Ancient Town");
        map.put("善卷洞", "Shanjuan Cave");
        map.put("宜兴竹海", "Yixing Bamboo Forest");

        // ===== 成都 =====
        map.put("宽窄巷子", "Kuanzhai Alley");
        map.put("锦里", "Jinli");
        map.put("武侯祠", "Wuhou Shrine");
        map.put("杜甫草堂", "Du Fu Thatched Cottage");
        map.put("大熊猫基地", "Giant Panda Base");
        map.put("熊猫基地", "Giant Panda Base");
        map.put("都江堰", "Dujiangyan");
        map.put("青城山", "Mount Qingcheng");
        map.put("九寨沟", "Jiuzhaigou");
        map.put("黄龙", "Huanglong");
        map.put("峨眉山", "Mount Emei");
        map.put("乐山大佛", "Leshan Giant Buddha");
        map.put("三星堆", "Sanxingdui");
        map.put("人民公园", "Chengdu People's Park");
        map.put("春熙路", "Chunxi Road");

        // ===== 云南 =====
        map.put("洱海", "Erhai Lake");
        map.put("泸沽湖", "Lugu Lake");
        map.put("玉龙雪山", "Jade Dragon Snow Mountain");
        map.put("丽江古城", "Lijiang Ancient Town");
        map.put("大研古城", "Dayan Ancient Town");
        map.put("束河古镇", "Shuhe Ancient Town");
        map.put("大理古城", "Dali Ancient Town");
        map.put("崇圣寺三塔", "Three Pagodas");
        map.put("石林", "Stone Forest");
        map.put("香格里拉", "Shangri-La");
        map.put("普达措", "Pudacuo National Park");
        map.put("西双版纳", "Xishuangbanna");
        map.put("苍山", "Cangshan Mountain");
        map.put("滇池", "Dianchi Lake");

        // ===== 其他知名景点 =====
        map.put("莫高窟", "Mogao Caves");
        map.put("鸣沙山", "Mingsha Mountain");
        map.put("月牙泉", "Crescent Moon Spring");
        map.put("嘉峪关", "Jiayuguan Pass");
        map.put("张掖丹霞", "Zhangye Danxia");
        map.put("黄山", "Yellow Mountain");
        map.put("宏村", "Hongcun Village");
        map.put("西递", "Xidi Village");
        map.put("庐山", "Mount Lu");
        map.put("三清山", "Mount Sanqing");
        map.put("武夷山", "Mount Wuyi");
        map.put("张家界", "Zhangjiajie");
        map.put("天门山", "Tianmen Mountain");
        map.put("凤凰古城", "Fenghuang Ancient Town");
        map.put("洞庭湖", "Dongting Lake");
        map.put("岳阳楼", "Yueyang Tower");
        map.put("黄鹤楼", "Yellow Crane Tower");
        map.put("滕王阁", "Tengwang Pavilion");
        map.put("鼓浪屿", "Gulangyu Island");
        map.put("南普陀寺", "Nanputuo Temple");
        map.put("曾厝垵", "Zengcuoan");
        map.put("日月潭", "Sun Moon Lake");
        map.put("阿里山", "Alishan");
        map.put("太鲁阁", "Taroko Gorge");

        // ===== 西藏 =====
        map.put("布达拉宫", "Potala Palace");
        map.put("大昭寺", "Jokhang Temple");
        map.put("纳木错", "Namtso Lake");
        map.put("羊卓雍错", "Yamdrok Lake");
        map.put("珠穆朗玛峰", "Mount Everest");

        // ===== 新疆 =====
        map.put("天山", "Tianshan Mountain");
        map.put("喀纳斯", "Kanas Lake");
        map.put("赛里木湖", "Sayram Lake");
        map.put("吐鲁番葡萄沟", "Turpan Grape Valley");
        map.put("火焰山", "Flaming Mountain");

        // ===== 青海/甘肃 =====
        map.put("青海湖", "Qinghai Lake");
        map.put("茶卡盐湖", "Chaka Salt Lake");
        map.put("敦煌", "Dunhuang");

        // ===== 广州/深圳 =====
        map.put("广州塔", "Canton Tower");
        map.put("小蛮腰", "Canton Tower");
        map.put("长隆", "Chimelong");
        map.put("世界之窗", "Window of the World");
        map.put("欢乐谷", "Happy Valley");
        map.put("东部华侨城", "OCT East");
        map.put("白云山", "Baiyun Mountain");
        map.put("沙面", "Shamian Island");
        map.put("孙中山故居", "Sun Yat-sen Memorial Hall");

        // ===== 香港/澳门 =====
        map.put("维多利亚港", "Victoria Harbour");
        map.put("太平山", "Victoria Peak");
        map.put("旺角", "Mong Kok");
        map.put("铜锣湾", "Causeway Bay");
        map.put("迪士尼乐园", "Hong Kong Disneyland");
        map.put("香港迪士尼", "Hong Kong Disneyland");
        map.put("海洋公园", "Hong Kong Ocean Park");
        map.put("大三巴", "Ruins of St. Paul");
        map.put("澳门威尼斯人", "The Venetian Macao");
        map.put("澳门塔", "Macau Tower");

        return Map.copyOf(map);
    }

    private static Map<String, String> createCityMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("北京", "Beijing");
        map.put("上海", "Shanghai");
        map.put("广州", "Guangzhou");
        map.put("深圳", "Shenzhen");
        map.put("杭州", "Hangzhou");
        map.put("南京", "Nanjing");
        map.put("成都", "Chengdu");
        map.put("重庆", "Chongqing");
        map.put("武汉", "Wuhan");
        map.put("西安", "Xi'an");
        map.put("苏州", "Suzhou");
        map.put("天津", "Tianjin");
        map.put("长沙", "Changsha");
        map.put("郑州", "Zhengzhou");
        map.put("东莞", "Dongguan");
        map.put("青岛", "Qingdao");
        map.put("沈阳", "Shenyang");
        map.put("宁波", "Ningbo");
        map.put("昆明", "Kunming");
        map.put("大连", "Dalian");
        map.put("厦门", "Xiamen");
        map.put("合肥", "Hefei");
        map.put("佛山", "Foshan");
        map.put("福州", "Fuzhou");
        map.put("哈尔滨", "Harbin");
        map.put("济南", "Jinan");
        map.put("温州", "Wenzhou");
        map.put("长春", "Changchun");
        map.put("石家庄", "Shijiazhuang");
        map.put("常州", "Changzhou");
        map.put("泉州", "Quanzhou");
        map.put("南宁", "Nanning");
        map.put("贵阳", "Guiyang");
        map.put("南昌", "Nanchang");
        map.put("太原", "Taiyuan");
        map.put("烟台", "Yantai");
        map.put("嘉兴", "Jiaxing");
        map.put("南通", "Nantong");
        map.put("金华", "Jinhua");
        map.put("珠海", "Zhuhai");
        map.put("惠州", "Huizhou");
        map.put("徐州", "Xuzhou");
        map.put("海口", "Haikou");
        map.put("乌鲁木齐", "Urumqi");
        map.put("绍兴", "Shaoxing");
        map.put("中山", "Zhongshan");
        map.put("台州", "Taizhou");
        map.put("兰州", "Lanzhou");
        map.put("芜湖", "Wuhu");
        map.put("三亚", "Sanya");
        map.put("呼和浩特", "Hohhot");
        map.put("银川", "Yinchuan");
        map.put("西宁", "Xining");
        map.put("拉萨", "Lhasa");
        map.put("桂林", "Guilin");
        map.put("丽江", "Lijiang");
        map.put("张家界", "Zhangjiajie");
        map.put("黄山", "Huangshan");
        map.put("大理", "Dali");
        map.put("洛阳", "Luoyang");
        map.put("秦皇岛", "Qinhuangdao");
        map.put("曲阜", "Qufu");
        map.put("无锡", "Wuxi");
        map.put("扬州", "Yangzhou");
        map.put("镇江", "Zhenjiang");
        map.put("湖州", "Huzhou");
        map.put("廊坊", "Langfang");
        map.put("遵义", "Zunyi");
        map.put("香港", "Hong Kong");
        map.put("澳门", "Macau");
        return Map.copyOf(map);
    }
}
