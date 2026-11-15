package com.ironfit.service;

import com.fasterxml.jackson.databind.*;
import com.ironfit.config.AppProperties;
import com.ironfit.entity.*;
import com.ironfit.repository.*;
import com.ironfit.exception.ApiException;
import com.ironfit.security.CurrentUser;
import com.stripe.model.checkout.Session;
import com.stripe.model.Invoice;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import jakarta.persistence.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.*;

@Service
public class StripeCheckoutService {
 private final AppProperties props;
 private final Environment env;
 private final EntityManager em;
 private final StripeOrderRepository orders;
 private final PaymentRepository payments;
 private final ObjectMapper json;
 public StripeCheckoutService(AppProperties props, Environment env, EntityManager em, StripeOrderRepository orders, PaymentRepository payments, ObjectMapper json) {
  this.props=props; this.env=env; this.em=em; this.orders=orders; this.payments=payments; this.json=json;
 }
 static int scale(String currency) { return "EUR".equalsIgnoreCase(currency) ? 2 : 3; }
 static long eurMinor(BigDecimal amount) {
  try {long cents=amount.movePointRight(2).longValueExact();if(cents<=0)throw new ArithmeticException();return cents;}
  catch(ArithmeticException e){throw ApiException.badRequest("Prix EUR invalide : deux décimales maximum.");}
 }
 private String setting(String key) { return env.getProperty(key, "").trim(); }
 private RequestOptions options(String id) {
  if(!"eur".equalsIgnoreCase(env.getProperty("STRIPE_CURRENCY", "eur"))) throw ApiException.badRequest("La devise des nouveaux paiements doit être EUR.");
  if (!props.stripeTestMode() || props.stripeSecretKey()==null || !props.stripeSecretKey().startsWith("sk_test_")) throw ApiException.badRequest("Stripe accepte uniquement les clés de test.");
  var builder=RequestOptions.builder().setApiKey(props.stripeSecretKey());
  if(id!=null) builder.setIdempotencyKey(id);
  return builder.build();
 }
 private String returnUrl() {
  String url=setting("STRIPE_RETURN_URL");
  try { var uri=java.net.URI.create(url); if(uri.getHost()==null || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1").contains(uri.getHost())))) throw new Exception(); }
  catch(Exception e){throw ApiException.badRequest("Configurez STRIPE_RETURN_URL (HTTPS ou localhost).");}
  return url;
 }
 private User lock(Long id) { User user=em.find(User.class,id,LockModeType.PESSIMISTIC_WRITE); if(user==null || !user.isMember()) throw ApiException.forbidden(); return user; }
 private StripeOrder owned(CurrentUser me,String id) { return orders.findById(id).filter(o->o.getMemberId().equals(me.id())).orElseThrow(()->ApiException.notFound("Paiement introuvable")); }
 private Map<String,String> metadata(StripeOrder o) {return Map.of("ironfit_order",o.getId());}
 static LocalDate extend(LocalDate from,String duration) {
  LocalDate base=from!=null && from.isAfter(LocalDate.now())?from:LocalDate.now();
  var m=Pattern.compile("^(\\d+)\\s*(mois|jours?|entrées?|séances?)$",Pattern.CASE_INSENSITIVE).matcher(duration==null?"":duration.trim());
  if(!m.matches()) throw ApiException.badRequest("La durée de cette formule doit être configurée avant le paiement en ligne.");
  int count=Integer.parseInt(m.group(1)); if(count<1 || count>3650) throw ApiException.badRequest("Durée invalide");
  return m.group(2).equalsIgnoreCase("mois")?base.plusMonths(count):base.plusDays(count);
 }
 @Transactional
 public Map<String,Object> checkout(CurrentUser me,Long planId,String requestId) throws Exception {
  options(null); if(!setting("STRIPE_WEBHOOK_SECRET").startsWith("whsec_")) throw ApiException.badRequest("Configurez le secret webhook Stripe avant de payer.");
  if(requestId==null || !requestId.matches("[a-zA-Z0-9-]{16,64}")) throw ApiException.badRequest("Identifiant de tentative invalide");
  User u=lock(me.id()); if(!u.isActive()) throw ApiException.forbidden();
  String id=me.id()+"-eur-"+requestId;
  StripeOrder existing=orders.findById(id).orElse(null);
  if(existing!=null) { if(!existing.getPlanId().equals(planId)) throw ApiException.conflict("Cette tentative concerne une autre formule"); return Map.of("order_id",id,"url",existing.getCheckoutUrl()); }
  Plan p=planId==null?null:em.find(Plan.class,planId); if(p==null) throw ApiException.notFound("Tarif introuvable");
  extend(null,p.getDuration());
  if(!"EUR".equals(p.getCurrency())) throw ApiException.badRequest("Le prix EUR de cette formule doit être confirmé.");
  var account=com.stripe.model.Account.retrieve(options(null));
  var country=com.stripe.model.CountrySpec.retrieve(account.getCountry(),options(null));
  if(!country.getSupportedPaymentCurrencies().contains("eur")) throw ApiException.badRequest("Ce compte Stripe ne prend pas en charge l’EUR. Configurez un compte de test compatible.");
  long amount=eurMinor(p.getPrice());
  String interval=setting("STRIPE_PLAN_"+planId+"_INTERVAL");
  String countText=setting("STRIPE_PLAN_"+planId+"_INTERVAL_COUNT");
  int count=countText.isEmpty()?1:Integer.parseInt(countText);
  if(!interval.isEmpty() && (!Set.of("day","week","month","year").contains(interval) || count<1)) throw ApiException.badRequest("Périodicité non configurée");
  if(!interval.isEmpty() && orders.findByMemberIdOrderByCreatedDesc(me.id()).stream().anyMatch(o->"subscription".equals(o.getMode()) && !Set.of("expired","canceled").contains(o.getStatus()))) throw ApiException.conflict("Gérez ou annulez votre abonnement existant avant de réessayer.");
  StripeOrder o=new StripeOrder(); o.setCurrency("EUR");o.setId(id);o.setMemberId(me.id());o.setPlanId(planId);o.setPlanName(p.getName());o.setDuration(p.getDuration());o.setSubtotal(amount);o.setMode(interval.isEmpty()?"payment":"subscription");
  Map<String,Object> product=new HashMap<>();product.put("name",p.getName());
  String taxCode=setting("STRIPE_PLAN_"+planId+"_TAX_CODE");if(!taxCode.isEmpty())product.put("tax_code",taxCode);
  Map<String,Object> price=new HashMap<>(Map.of("currency","eur","unit_amount",amount,"product_data",product,"tax_behavior","exclusive"));
  if(!interval.isEmpty())price.put("recurring",Map.of("interval",interval,"interval_count",count));
  boolean tax=Boolean.parseBoolean(setting("STRIPE_AUTOMATIC_TAX"));
  if(tax && taxCode.isEmpty()) throw ApiException.badRequest("Configurez le code fiscal Stripe de cette formule.");
  Map<String,Object> params=new HashMap<>();params.put("mode",o.getMode());params.put("line_items",List.of(Map.of("price_data",price,"quantity",1)));params.put("payment_method_types",List.of("card"));
  if(u.getStripeCustomerId()==null) {
   var customer=com.stripe.model.Customer.create(Map.of("email",u.getEmail(),"name",u.getName(),"metadata",Map.of("ironfit_user",u.getId().toString())),options("customer-"+u.getId()));
   u.setStripeCustomerId(customer.getId());
  }
  params.put("customer",u.getStripeCustomerId());params.put("customer_update",Map.of("address","auto","name","auto"));params.put("metadata",metadata(o));params.put("client_reference_id",id);
  params.put("success_url",returnUrl());params.put("cancel_url",returnUrl());params.put("automatic_tax",Map.of("enabled",tax));params.put("billing_address_collection","required");
  if(interval.isEmpty()) {params.put("invoice_creation",Map.of("enabled",true));} else params.put("subscription_data",Map.of("metadata",metadata(o)));
  Session session=Session.create(params,options("checkout-"+id));
  if(Boolean.TRUE.equals(session.getLivemode())) throw ApiException.forbidden("Les paiements réels sont interdits.");
  o.setCustomerId(u.getStripeCustomerId());o.setSessionId(session.getId());o.setCheckoutUrl(session.getUrl());orders.save(o);
  return Map.of("order_id",id,"url",session.getUrl());
 }
 @Transactional
 public Map<String,Object> status(CurrentUser me,String id) throws Exception {
  lock(me.id());StripeOrder o=owned(me,id);Session session=Session.retrieve(o.getSessionId(),options(null));
  if(!"paid".equals(o.getStatus()) && "expired".equals(session.getStatus()))o.setStatus("expired");
  if(o.getSubscriptionId()!=null) {
   var subscription=com.stripe.model.Subscription.retrieve(o.getSubscriptionId(),options(null));
   if(!"paid".equals(o.getStatus()) || !"active".equals(subscription.getStatus()))o.setStatus(subscription.getStatus());
  }
  var result=new HashMap<String,Object>();result.put("plan_id",o.getPlanId());result.put("status",o.getStatus());result.put("plan",o.getPlanName());result.put("amount",session.getAmountTotal()==null?null:BigDecimal.valueOf(session.getAmountTotal(),scale(o.getCurrency())));result.put("currency",o.getCurrency());result.put("mode",o.getMode());result.put("checkout_status",session.getStatus());
  return result;
 }
 @Transactional
 public void cancel(CurrentUser me,String id) throws Exception {
  lock(me.id());StripeOrder o=owned(me,id);
  Session session=Session.retrieve(o.getSessionId(),options(null));
  if("open".equals(session.getStatus()))session=session.expire(options("expire-"+id));
  if("expired".equals(session.getStatus()))o.setStatus("expired");
  else throw ApiException.conflict("Cette session est déjà terminée. Vérifiez son paiement.");
 }
 @Transactional(readOnly=true)
 public List<Map<String,Object>> history(CurrentUser me) {
  return payments.findByMemberIdOrderByIdDesc(me.id()).stream().filter(p->p.getStripeReference()!=null).map(p->{Map<String,Object> r=new HashMap<>();r.put("amount",p.getAmount());r.put("currency",p.getCurrency());r.put("date",p.getPaidOn());r.put("description",p.getNote());r.put("status","paid");r.put("receipt_url",p.getReceiptUrl());return r;}).toList();
 }
 @Transactional(readOnly=true)
 public Map<String,String> portal(CurrentUser me) throws Exception {
  String customer=orders.findByMemberIdOrderByCreatedDesc(me.id()).stream().map(StripeOrder::getCustomerId).filter(Objects::nonNull).findFirst().orElseThrow(()->ApiException.badRequest("Aucun compte de facturation disponible"));
  var session=com.stripe.model.billingportal.Session.create(Map.of("customer",customer,"return_url",returnUrl()),options(null));return Map.of("url",session.getUrl());
 }
 @Transactional
 public void webhook(String payload,String signature) throws Exception {
  options(null);String secret=setting("STRIPE_WEBHOOK_SECRET");if(!secret.startsWith("whsec_"))throw ApiException.badRequest("Webhook non configuré");
  com.stripe.model.Event event;
  try{event=Webhook.constructEvent(payload,signature,secret);}catch(Exception e){throw ApiException.badRequest("Signature Stripe invalide");}
  if(Boolean.TRUE.equals(event.getLivemode()))throw ApiException.badRequest("Événement live interdit");
  JsonNode data=json.readTree(payload).path("data").path("object");String type=event.getType();
  boolean invoice="invoice.paid".equals(type) || "invoice.payment_failed".equals(type);
  boolean checkout=Set.of("checkout.session.completed","checkout.session.async_payment_succeeded","checkout.session.expired","checkout.session.async_payment_failed").contains(type);
  boolean subscriptionEvent=Set.of("customer.subscription.updated","customer.subscription.deleted").contains(type); if(!invoice && !checkout && !subscriptionEvent)return;
  String id=data.path("metadata").path("ironfit_order").asText();
  if(invoice){id=data.path("parent").path("subscription_details").path("metadata").path("ironfit_order").asText();if(id.isEmpty())id=data.path("subscription_details").path("metadata").path("ironfit_order").asText();}
  if(id.isEmpty())return;
  StripeOrder found=orders.findById(id).orElseThrow(()->new IllegalStateException("Order not yet persisted; retry webhook"));
  User user=lock(found.getMemberId());StripeOrder o=em.find(StripeOrder.class,id,LockModeType.PESSIMISTIC_WRITE);em.refresh(o);
  if(subscriptionEvent){ var current=com.stripe.model.Subscription.retrieve(data.path("id").asText(),options(null));o.setSubscriptionId(current.getId());o.setCustomerId(current.getCustomer());o.setStatus(current.getStatus());return;} if("invoice.payment_failed".equals(type)){
   String subscription=data.path("parent").path("subscription_details").path("subscription").asText();
   if(subscription.isEmpty())subscription=data.path("subscription").asText();
   if(!subscription.isEmpty()){var current=com.stripe.model.Subscription.retrieve(subscription,options(null));o.setStatus(current.getStatus());}
   return;
  } if(checkout && !o.getSessionId().equals(data.path("id").asText()))throw ApiException.badRequest("Session incorrecte");
  if(checkout){
   if(!data.path("customer").asText().isEmpty())o.setCustomerId(data.path("customer").asText());
   if(!data.path("subscription").isNull() && !data.path("subscription").asText().isEmpty())o.setSubscriptionId(data.path("subscription").asText());
   if(type.endsWith("expired")){if(!"paid".equals(o.getStatus()))o.setStatus("expired");return;}
   if(type.endsWith("failed")){if(!"paid".equals(o.getStatus()))o.setStatus("failed");return;}
   if("subscription".equals(o.getMode()))return;
   if(!"paid".equals(data.path("payment_status").asText()))return;
   if(data.path("amount_subtotal").asLong()!=o.getSubtotal())throw ApiException.badRequest("Montant incorrect");
  } else if(!"subscription".equals(o.getMode()) || !data.path("paid").asBoolean())return;
  if(!o.getCurrency().equalsIgnoreCase(data.path("currency").asText()))throw ApiException.badRequest("Devise incorrecte");
  String reference=data.path("id").asText();if(payments.existsByStripeReference(reference))return;
  Plan plan=em.find(Plan.class,o.getPlanId());if(plan==null)throw new IllegalStateException("Plan removed before fulfillment");
  LocalDate end;
  if(invoice){
   long periodEnd=0;for(JsonNode line:data.path("lines").path("data"))periodEnd=Math.max(periodEnd,line.path("period").path("end").asLong());
   if(periodEnd==0)throw new IllegalStateException("Missing invoice period");
   end=Instant.ofEpochSecond(periodEnd).atZone(ZoneId.of("Africa/Tunis")).toLocalDate();
   if(user.getSubEnd()!=null && user.getSubEnd().isAfter(end))end=user.getSubEnd();
  } else end=extend(user.getSubEnd(),o.getDuration());
  user.setPlan(plan);user.setSubEnd(end);
  Payment p=new Payment();p.setMember(user);p.setMethod("Stripe test");p.setNote("Abonnement "+o.getPlanName());p.setPaidOn(LocalDate.now());p.setStripeReference(reference);p.setCurrency(o.getCurrency());p.setAmountMinor(data.path(invoice?"amount_paid":"amount_total").asLong());p.setAmount(BigDecimal.valueOf(p.getAmountMinor(),scale(o.getCurrency())));
  if(invoice)p.setReceiptUrl(data.path("hosted_invoice_url").asText(null));
  else if(!data.path("invoice").isNull() && !data.path("invoice").asText().isEmpty())p.setReceiptUrl(Invoice.retrieve(data.path("invoice").asText(),options(null)).getHostedInvoiceUrl());
  payments.save(p);o.setStatus("paid");
 }
}

