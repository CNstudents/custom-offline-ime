package com.dingzhi.ime.core

/**
 * 标准汉语拼音音节表（不带声调）。
 *
 * 用途：把「nihao」这种连续拼音切分成「ni + hao」，
 * 从而得到简拼（首字母串）「nh」，用于简拼匹配。
 *
 * 说明：这里是一份静态常量表，不占用词库文件体积，
 * 也不依赖任何第三方拼音库，保证离线与低内存占用。
 */
object PinyinSyllables {

    private val SYLLABLES = HashSet<String>(512)
    private var maxLength = 1

    init {
        val table = "" +
            "a ai an ang ao " +
            "ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu " +
            "ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo " +
            "da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo " +
            "e ei en eng er " +
            "fa fan fang fei fen feng fo fou fu " +
            "ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo " +
            "ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo " +
            "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun " +
            "ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo " +
            "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu long lou lu luan lun luo lv lve " +
            "ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu " +
            "na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nuo nv nve " +
            "o ou " +
            "pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu " +
            "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun " +
            "ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo " +
            "sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo " +
            "ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo " +
            "wa wai wan wang wei wen weng wo wu " +
            "xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun " +
            "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun " +
            "za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo"

        for (item in table.split(" ")) {
            if (item.isNotEmpty()) {
                SYLLABLES.add(item)
                if (item.length > maxLength) maxLength = item.length
            }
        }
    }

    /** 判断是否是一个合法音节 */
    fun isSyllable(text: String): Boolean = SYLLABLES.contains(text)

    /**
     * 计算简拼：按「最长匹配」把拼音切成音节，取每个音节首字母。
     * 无法切分时退化为逐字母处理，保证永远有返回值。
     */
    fun initialsOf(pinyin: String): String {
        if (pinyin.isEmpty()) return ""
        val sb = StringBuilder(pinyin.length / 2 + 1)
        var index = 0
        while (index < pinyin.length) {
            var matched = 0
            var len = if (maxLength < pinyin.length - index) maxLength else pinyin.length - index
            while (len >= 1) {
                if (SYLLABLES.contains(pinyin.substring(index, index + len))) {
                    matched = len
                    break
                }
                len--
            }
            if (matched == 0) {
                sb.append(pinyin[index])
                index++
            } else {
                sb.append(pinyin[index])
                index += matched
            }
        }
        return sb.toString()
    }
}
