package com.example.data.quran

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface QuranComApi {
  @GET("verses/by_page/{page}")
  suspend fun getVersesByPageRaw(
    @Path("page") page: Int,
    @Query("words") words: Boolean = true,
    @Query("word_fields") wordFields: String = "code_v2,text_uthmani,page_number,line_number",
    @Query("fields") fields: String = "text_uthmani,page_number,juz_number,hizb_number",
    @Query("per_page") perPage: Int = 50,
  ): Response<ResponseBody>

  @GET("verses/by_key/{key}")
  suspend fun getVerseByKeyRaw(
    @Path("key") verseKey: String,
    @Query("words") words: Boolean = true,
    @Query("word_fields") wordFields: String = "code_v2,text_uthmani,page_number,line_number",
    @Query("fields") fields: String = "text_uthmani,page_number,juz_number,hizb_number",
  ): Response<ResponseBody>

  @GET("verses/by_juz/{juz}")
  suspend fun getFirstVerseByJuzRaw(
    @Path("juz") juz: Int,
    @Query("per_page") perPage: Int = 1,
    @Query("page") page: Int = 1,
  ): Response<ResponseBody>

  @GET("chapters")
  suspend fun getChaptersRaw(
    @Query("language") language: String = "ar",
  ): Response<ResponseBody>

  @GET("juzs")
  suspend fun getJuzsRaw(): Response<ResponseBody>

  @GET("resources/translations")
  suspend fun getTranslationsResourceRaw(
    @Query("language") language: String,
  ): Response<ResponseBody>

  @GET("resources/tafsirs")
  suspend fun getTafsirsResourceRaw(): Response<ResponseBody>

  @GET("verses/by_key/{key}")
  suspend fun getVerseTranslationsRaw(
    @Path("key") verseKey: String,
    @Query("translations") translationIds: String,
  ): Response<ResponseBody>

  @GET("tafsirs/{tafsirId}/by_ayah/{key}")
  suspend fun getTafsirByAyahRaw(
    @Path("tafsirId") tafsirId: Int,
    @Path("key") verseKey: String,
  ): Response<ResponseBody>

  @GET("search")
  suspend fun searchQuranRaw(
    @Query("q") query: String,
    @Query("size") size: Int = 25,
    @Query("page") page: Int = 1,
    @Query("language") language: String = "ar",
  ): Response<ResponseBody>

  @GET("resources/chapter_reciters")
  suspend fun getChapterRecitersRaw(
    @Query("language") language: String = "ar",
  ): Response<ResponseBody>

  @GET("chapter_recitations/{reciterId}/{chapter}")
  suspend fun getChapterRecitationWithSegmentsRaw(
    @Path("reciterId") reciterId: Int,
    @Path("chapter") chapter: Int,
    @Query("segments") segments: Boolean = true,
  ): Response<ResponseBody>
}

interface AlQuranCloudApi {
  @GET("page/{page}/quran-tajweed")
  suspend fun getTajweedPageRaw(
    @Path("page") page: Int,
  ): Response<ResponseBody>

  @GET("edition/format/audio")
  suspend fun getAudioEditionsRaw(): Response<ResponseBody>

  @GET("surah/{surah}/{edition}")
  suspend fun getSurahAudioRaw(
    @Path("surah") surah: Int,
    @Path("edition") edition: String,
  ): Response<ResponseBody>
}

object QuranApiClient {
  val moshi: Moshi = Moshi.Builder()
    .addLast(KotlinJsonAdapterFactory())
    .build()

  val okHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .build()

  val quranComApi: QuranComApi = Retrofit.Builder()
    .baseUrl("https://api.quran.com/api/v4/")
    .client(okHttpClient)
    .addConverterFactory(MoshiConverterFactory.create(moshi))
    .build()
    .create(QuranComApi::class.java)

  val alQuranCloudApi: AlQuranCloudApi = Retrofit.Builder()
    .baseUrl("https://api.alquran.cloud/v1/")
    .client(okHttpClient)
    .addConverterFactory(MoshiConverterFactory.create(moshi))
    .build()
    .create(AlQuranCloudApi::class.java)
}
