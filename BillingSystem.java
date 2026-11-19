import java.util.*;

public class BillingSystem {

    // ---------------- domain ----------------

    private enum DiscountCode { P10, P20, FLAT100, REDEEM }

    private enum Level {
        BRONZE, SILVER, GOLD, PLATINUM;

        static Level of(long points) {
            if (points >= 2000) return PLATINUM;
            if (points >= 500) return GOLD;
            if (points >= 100) return SILVER;
            return BRONZE;
        }
    }

    private static final class Customer {
        long points = 0;
    }

    private static final class Bill {
        final String id;
        final Customer customer;
        final long subtotal;
        final EnumSet<DiscountCode> applied = EnumSet.noneOf(DiscountCode.class);
        boolean paid = false;

        Bill(String id, Customer customer, long subtotal) {
            this.id = id;
            this.customer = customer;
            this.subtotal = subtotal;
        }
    }

    /** Result of evaluating all applied codes against the current state. */
    private static final class Quote {
        final long payable;
        final long redeemed; // points that will be deducted if the bill is paid

        Quote(long payable, long redeemed) {
            this.payable = payable;
            this.redeemed = redeemed;
        }
    }

    // ---------------- state ----------------

    private final Map<String, Customer> customers = new HashMap<>();
    private final Map<String, Bill> bills = new HashMap<>();
    private long billCounter = 0;

    // ---------------- API ----------------

    public synchronized String createBill(String customerId, List<String> cartItems) {
        if (customerId == null || customerId.isEmpty() || cartItems == null || cartItems.isEmpty()) {
            return "ERROR";
        }
        long subtotal = 0;
        try {
            for (String item : cartItems) {
                subtotal = Math.addExact(subtotal, parseItem(item));
            }
        } catch (IllegalArgumentException | ArithmeticException e) {
            return "ERROR";
        }

        // Only a successfully created bill consumes an ID.
        String id = "B" + (++billCounter);
        Customer customer = customers.computeIfAbsent(customerId, k -> new Customer());
        bills.put(id, new Bill(id, customer, subtotal));
        return id;
    }

    public synchronized long applyDiscount(String billId, String discountCode) {
        Bill bill = bills.get(billId);
        if (bill == null || bill.paid) return -1;

        DiscountCode code = parseCode(discountCode);
        if (code != null) bill.applied.add(code); // set => idempotent
        return evaluate(bill).payable;
    }

    public synchronized String payBill(String billId, long amountPaid) {
        Bill bill = bills.get(billId);
        if (bill == null || bill.paid) return "ERROR";

        // Re-evaluate at payment time: the customer's points may have changed
        // since REDEEM was applied (e.g. another bill was paid in between).
        Quote quote = evaluate(bill);
        if (amountPaid != quote.payable) return "ERROR";

        bill.paid = true;
        Customer c = bill.customer;
        c.points -= quote.redeemed;                  // 1) redeem
        long earned = quote.payable / 100;           // 2) earn
        c.points += earned;

        return "PAID|final=" + quote.payable
                + "|pointsEarned=" + earned
                + "|totalPoints=" + c.points
                + "|level=" + Level.of(c.points);
    }

    // ---------------- pricing ----------------

    private Quote evaluate(Bill bill) {
        long payable = bill.subtotal;

        // 1) effective percentage: highest one wins
        int percent = 0;
        if (bill.applied.contains(DiscountCode.P20)) percent = 20;
        else if (bill.applied.contains(DiscountCode.P10)) percent = 10;
        if (percent > 0) payable -= percentOf(bill.subtotal, percent);

        // 2) flat discount, only if the subtotal qualifies
        if (bill.applied.contains(DiscountCode.FLAT100) && bill.subtotal >= 500) {
            payable = Math.max(0, payable - 100);
        }

        // 3) redemption: 1 point = 1 dollar, capped at 20% of the current payable
        long redeemed = 0;
        if (bill.applied.contains(DiscountCode.REDEEM)) {
            long cap = percentOf(payable, 20);
            redeemed = Math.min(bill.customer.points, cap);
            payable -= redeemed;
        }
        return new Quote(Math.max(0, payable), redeemed);
    }

    /** floor(value * percent / 100) without overflowing for large values. */
    private static long percentOf(long value, int percent) {
        return (value / 100) * percent + ((value % 100) * percent) / 100;
    }

    // ---------------- parsing ----------------

    /** Returns unitPrice * quantity or throws IllegalArgumentException / ArithmeticException. */
    private static long parseItem(String item) {
        if (item == null) throw new IllegalArgumentException();
        String[] parts = item.split("\\|", -1);
        if (parts.length != 3 || parts[0].isEmpty()) throw new IllegalArgumentException();
        long unitPrice = Long.parseLong(parts[1].trim()); // NumberFormatException is an IllegalArgumentException
        long quantity = Long.parseLong(parts[2].trim());
        if (unitPrice < 0 || quantity <= 0) throw new IllegalArgumentException();
        return Math.multiplyExact(unitPrice, quantity);
    }

    private static DiscountCode parseCode(String code) {
        if (code == null) return null;
        try {
            return DiscountCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null; // unknown codes are ignored
        }
    }

    public static void main(String[] args) {
        BillingSystem s = new BillingSystem();

        System.out.println(s.createBill("C1", List.of("book|200|1", "pen|10|5"))); // B1
        System.out.println(s.applyDiscount("B1", "P10"));                          // 225
        System.out.println(s.applyDiscount("B1", "FLAT100"));                      // 225
        System.out.println(s.payBill("B1", 225));   // PAID|final=225|pointsEarned=2|totalPoints=2|level=BRONZE

        System.out.println(s.createBill("C1", List.of("shoes|600|1", "tshirt|200|2"))); // B2
        System.out.println(s.applyDiscount("B2", "P20"));                          // 800
        System.out.println(s.applyDiscount("B2", "FLAT100"));                      // 700
        System.out.println(s.applyDiscount("B2", "REDEEM"));                       // 698
        System.out.println(s.payBill("B2", 698));   // PAID|final=698|pointsEarned=6|totalPoints=6|level=BRONZE

        System.out.println(s.createBill("C2", List.of("mouse|499|1")));            // B3
        System.out.println(s.applyDiscount("B3", "P10"));                          // 450
        System.out.println(s.payBill("B3", 449));                                  // ERROR
    }
}